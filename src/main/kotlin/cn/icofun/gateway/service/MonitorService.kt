package cn.icofun.gateway.service

import cn.icofun.gateway.model.entity.GatewayConfigHistoryEntity
import cn.icofun.gateway.repository.GatewayConfigHistoryRepository
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

@Service
class MonitorService(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val historyRepository: GatewayConfigHistoryRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private data class MetricEvent(
        val routeId: String,
        val path: String,
        val status: Int,
        val duration: Long,
        val timestamp: Long
    )

    private val metricSink = Sinks.many().multicast().onBackpressureBuffer<MetricEvent>(
        10000,
        false
    )

    companion object {
        const val STATUS_CLOSED = "CLOSED"
        const val STATUS_OPEN = "OPEN"
        const val STATUS_HALF_OPEN = "HALF_OPEN"

        private const val KEY_PREFIX = "gateway:metric"
        private const val STATUS_PREFIX = "gateway:status"
    }

    @PostConstruct
    fun init() {
        metricSink.asFlux()
            .bufferTimeout(1000, Duration.ofSeconds(1))
            .publishOn(Schedulers.boundedElastic())
            .flatMap { events -> processBatchMetrics(events) }
            .doOnError { e -> log.error("Error in metrics collector loop", e) }
            .subscribe()
    }

    /**
     * 核心：批量处理逻辑
     * 1. 内存聚合：将 List<Event> 合并为 Map 统计数据
     * 2. 批量写入：使用 Redis Pipeline 效果
     */
    private fun processBatchMetrics(events: List<MetricEvent>): Mono<Void> {
        if (events.isEmpty()) return Mono.empty()

        val tasks = mutableListOf<Mono<*>>()
        val now = LocalDateTime.now()
        val dateKey = getDateKey()
        val currentMinute = now.hour * 60 + now.minute
        val currentTimestamp = events.last().timestamp // 使用这批数据最新的时间戳

        // --- A. 内存聚合 (Memory Aggregation) ---
        var totalReq = 0L
        var totalErr = 0L
        val routeReqMap = HashMap<String, Long>() // routeId -> count
        val routeErrMap = HashMap<String, Long>() // routeId -> count
        val routeQpsMap = HashMap<String, Long>() // routeId -> count (Current Window)

        // 异常熔断检测专用
        val circuitCheckMap = HashMap<String, Long>() // routeId -> errCount

        events.forEach { event ->
            // 1. 全局计数
            totalReq++

            // 2. 路由计数
            routeReqMap[event.routeId] = (routeReqMap[event.routeId] ?: 0L) + 1
            routeQpsMap[event.routeId] = (routeQpsMap[event.routeId] ?: 0L) + 1

            // 3. 错误处理
            if (event.status >= 400) {
                totalErr++
                routeErrMap[event.routeId] = (routeErrMap[event.routeId] ?: 0L) + 1
            }

            if (event.status >= 500) {
                circuitCheckMap[event.routeId] = (circuitCheckMap[event.routeId] ?: 0L) + 1
            }

            // 4. 慢请求审计 (慢请求不聚合，因为需要详细信息，或者也可以单独抽样)
            if (event.duration > 3000) {
                // 异步触发，不阻塞主流程
                triggerAutoAudit(event.routeId, event.path, event.status, "Slow Request Warning (>${event.duration}ms)")
            }
        }

        // --- B. 组装 Redis 命令 (Batch Operations) ---

        // 1. 基础统计 (合并为单次调用)
        if (totalReq > 0) {
            tasks.add(redisTemplate.opsForValue().increment("$dateKey:total", totalReq))
            tasks.add(
                redisTemplate.opsForHash<String, String>()
                    .increment("$dateKey:trend", currentMinute.toString(), totalReq)
            )

            // 全局 QPS
            val globalQpsKey = getGlobalQpsKey(currentTimestamp)
            tasks.add(
                redisTemplate.opsForValue().increment(globalQpsKey, totalReq)
                    .flatMap { redisTemplate.expire(globalQpsKey, Duration.ofMinutes(5)) }
            )

            // 更新最大 QPS (简单采样，每批次最后做一次检测即可)
            if (currentTimestamp % 5 == 0L) {
                tasks.add(updateMaxQps(globalQpsKey))
            }
        }
        // 2. 路由请求统计 (使用 Loop 但已经是聚合后的数据)
        routeReqMap.forEach { (routeId, count) ->
            tasks.add(redisTemplate.opsForHash<String, String>().increment("$dateKey:route_req", routeId, count))
        }

        // 3. 路由实时 QPS
        val routeQpsKey = getRouteQpsKey(currentTimestamp)
        routeQpsMap.forEach { (routeId, count) ->
            tasks.add(redisTemplate.opsForHash<String, String>().increment(routeQpsKey, routeId, count))
        }
        // 统一设置过期时间 (仅需设置一次)
        tasks.add(redisTemplate.expire(routeQpsKey, Duration.ofMinutes(1)))

        // 4. 异常统计
        if (totalErr > 0) {
            tasks.add(redisTemplate.opsForValue().increment("$dateKey:error", totalErr))
        }
        routeErrMap.forEach { (routeId, count) ->
            tasks.add(redisTemplate.opsForHash<String, String>().increment("$dateKey:route_err", routeId, count))
        }

        // 5. 熔断检测 (聚合后的检测)
        circuitCheckMap.forEach { (routeId, count) ->
            tasks.add(checkCircuitBreakerBatch(routeId, count))
        }

        // --- C. 执行 Pipeline ---
        // Flux.merge 会并行执行这些 Mono，底层 Netty 客户端会自动进行 Pipelining 优化
        return Flux.merge(tasks)
            .then()
            .onErrorResume { e ->
                log.error("Failed to flush metrics batch", e)
                Mono.empty()
            }
    }

    fun recordMetrics(routeId: String, path: String, status: Int, duration: Long) {
        // Fire-and-forget: 只是往内存队列里塞一个对象，微秒级耗时
        metricSink.emitNext(
            MetricEvent(routeId, path, status, duration, Instant.now().epochSecond),
            Sinks.EmitFailureHandler.FAIL_FAST // 如果队列满了直接丢弃，不阻塞请求，保证网关稳定性
        )
    }

    private fun getRouteQpsKey(timestamp: Long) = "$KEY_PREFIX:route_qps:v2:$timestamp"

    // --- Key 生成辅助方法 ---
    private fun getDateKey() = "$KEY_PREFIX:daily:${LocalDateTime.now().format(DateTimeFormatter.BASIC_ISO_DATE)}"
    private fun getRouteStatusKey(routeId: String) = "$STATUS_PREFIX:route:$routeId"
    private fun getOpenRoutesSetKey() = "$STATUS_PREFIX:open_routes"
    private fun getMaxQpsKey() = "$KEY_PREFIX:max_qps:${LocalDateTime.now().format(DateTimeFormatter.BASIC_ISO_DATE)}"
    private fun getGlobalQpsKey(timestamp: Long) = "$KEY_PREFIX:global:qps:$timestamp"

    fun getRouteStatus(routeId: String): Mono<String> {
        return redisTemplate.opsForValue().get(getRouteStatusKey(routeId))
            .defaultIfEmpty(STATUS_CLOSED)
    }

    fun setRouteStatus(routeId: String, status: String, operator: String, reason: String): Mono<Boolean> {
        val updateStatusMono = redisTemplate.opsForValue().set(getRouteStatusKey(routeId), status)

        val updateSetMono = if (status == STATUS_OPEN) {
            redisTemplate.opsForSet().add(getOpenRoutesSetKey(), routeId)
        } else {
            redisTemplate.opsForSet().remove(getOpenRoutesSetKey(), routeId)
        }

        val actionDesc = when (status) {
            STATUS_OPEN -> "Circuit Breaker Opened"
            STATUS_HALF_OPEN -> "Circuit Breaker Half-Open"
            else -> "Circuit Breaker Closed"
        }

        val auditMono = recordSystemAudit(
            routeId,
            "CIRCUIT_BREAKER",
            actionDesc,
            mapOf("status" to status, "reason" to reason),
            operator
        )

        return updateStatusMono.then(updateSetMono).then(auditMono).thenReturn(true)
    }

    /**
     * 【新增】获取当前所有路由的实时 QPS
     * 取最近 3 秒的数据取平均值，使曲线更平滑
     */
    fun getCurrentRouteQps(): Mono<Map<String, Double>> {
        val now = Instant.now().epochSecond
        // 取过去 3 秒的 Key
        val keys = (0..2).map { getRouteQpsKey(now - it) }

        return Flux.fromIterable(keys)
            .flatMap { key ->
                redisTemplate.opsForHash<String, String>().entries(key)
            }
            .collectList()
            .map { list ->
                // 聚合计算: Map<RouteId, TotalCountIn3Secs>
                val totalMap = mutableMapOf<String, Double>()
                list.forEach { entry ->
                    val routeId = entry.key
                    val count = entry.value.toDoubleOrNull() ?: 0.0
                    totalMap.merge(routeId, count) { a, b -> a + b }
                }
                // 计算平均 QPS (除以采样时间窗口 3)
                totalMap.mapValues { it.value / 3.0 }
            }
            .defaultIfEmpty(emptyMap())
    }

    private fun checkCircuitBreakerBatch(routeId: String, errorDelta: Long): Mono<Void> {
        val currentMinuteKey = "$KEY_PREFIX:err_window:$routeId:${LocalDateTime.now().minute}"

        return redisTemplate.opsForValue().increment(currentMinuteKey, errorDelta)
            .flatMap { totalErrCount ->
                val expireMono = if (totalErrCount == errorDelta) {
                    redisTemplate.expire(currentMinuteKey, Duration.ofSeconds(65))
                } else Mono.empty()

                val triggerMono = if (totalErrCount >= 50) {
                    log.error("!!! AUTO CIRCUIT BREAKER TRIGGERED for route: $routeId !!!")
                    setRouteStatus(routeId, STATUS_OPEN, "SYSTEM_MONITOR", "High Error Rate (>50/min)").then()
                } else Mono.empty()

                expireMono.then(triggerMono)
            }
    }

    private fun updateMaxQps(currentQpsKey: String): Mono<Void> {
        return redisTemplate.opsForValue().get(currentQpsKey)
            .flatMap { current ->
                val maxKey = getMaxQpsKey()
                redisTemplate.opsForValue().get(maxKey).defaultIfEmpty("0")
                    .flatMap { max ->
                        if (current != null && current.toLong() > max.toLong()) {
                            redisTemplate.opsForValue().set(maxKey, current, Duration.ofDays(1))
                        } else Mono.empty()
                    }
            }.then()
    }

    fun getDashboardStats(): Mono<Map<String, Any>> {
        val dateKey = getDateKey()
        val now = Instant.now().epochSecond

        // 批量获取最近5秒的 QPS 求平均
        val qpsKeys = (0..9).map { getGlobalQpsKey(now - it) }

        return Mono.zip(
            redisTemplate.opsForValue().get("$dateKey:total").defaultIfEmpty("0"),
            redisTemplate.opsForValue().get("$dateKey:error").defaultIfEmpty("0"),
            redisTemplate.opsForValue().get(getMaxQpsKey()).defaultIfEmpty("0"),
            redisTemplate.opsForValue().multiGet(qpsKeys)
        ).map { tuple ->
            val validValues = tuple.t4.filterNotNull().map { it.toLong() }
            val avgQps = if (validValues.isNotEmpty()) validValues.sum() / validValues.size else 0

            mapOf(
                "total" to tuple.t1.toLong(),
                "error" to tuple.t2.toLong(),
                "qps" to avgQps,
                "todayMaxQps" to tuple.t3.toLong(),
                "uptime" to ManagementFactory.getRuntimeMXBean().uptime / 1000
            )
        }
    }

    fun getTrendData(): Mono<List<Int>> {
        return redisTemplate.opsForHash<String, String>().entries("${getDateKey()}:trend")
            .collectMap({ it.key.toInt() }, { it.value.toInt() })
            .map { map -> List(1440) { i -> map[i] ?: 0 } }
            .defaultIfEmpty(List(1440) { 0 })
    }

    fun getServiceRankings(): Mono<List<Map<String, Any>>> {
        val dateKey = getDateKey()
        return Mono.zip(
            redisTemplate.opsForHash<String, String>().entries("$dateKey:route_req")
                .collectMap({ it.key }, { it.value.toLong() }),
            redisTemplate.opsForHash<String, String>().entries("$dateKey:route_err")
                .collectMap({ it.key }, { it.value.toLong() })
        ).map { tuple ->
            val reqMap = tuple.t1
            val errMap = tuple.t2

            // 1. 创建临时 Map 用于聚合数据
            val aggregatedReq = mutableMapOf<String, Long>()
            val aggregatedErr = mutableMapOf<String, Long>()

            // 2. 遍历原始数据，统一清洗 ID 并累加
            reqMap.forEach { (id, count) ->
                val cleanId = if (id.startsWith("ReactiveCompositeDiscoveryClient_")) {
                    id.replace("ReactiveCompositeDiscoveryClient_", "")
                } else {
                    id
                }
                // 将具有相同 cleanId 的请求数累加
                aggregatedReq.merge(cleanId, count) { a, b -> a + b }
                // 同步处理错误数
                val errCount = errMap[id] ?: 0L
                aggregatedErr.merge(cleanId, errCount) { a, b -> a + b }
            }

            // 3. 将聚合后的结果转换为前端需要的 List 格式
            aggregatedReq.map { (cleanId, total) ->
                val err = aggregatedErr[cleanId] ?: 0L
                val rate = if (total > 0) (1.0 - err.toDouble() / total) * 100 else 100.0

                mapOf<String, Any>(
                    "name" to cleanId,
                    "total" to total,
                    "error" to err,
                    "successRate" to String.format(Locale.US, "%.2f", rate).toDouble()
                )
            }.sortedByDescending { it["total"] as Long }
        }.defaultIfEmpty(emptyList())
    }

    @Scheduled(fixedRate = 30000)
    fun autoRecoverTask() {
        redisTemplate.opsForSet().members(getOpenRoutesSetKey())
            .flatMap { routeId ->
                log.info("Probing route: $routeId")
                setRouteStatus(routeId, STATUS_HALF_OPEN, "SYSTEM_MONITOR", "Auto-transition to Half-Open")
            }.subscribe()
    }

    fun tryCloseCircuit(routeId: String): Mono<Boolean> {
        return getRouteStatus(routeId).flatMap { current ->
            if (current == STATUS_HALF_OPEN) {
                setRouteStatus(routeId, STATUS_CLOSED, "SYSTEM_MONITOR", "Service recovered")
            } else Mono.just(false)
        }
    }

    fun recordSystemAudit(
        configId: String,
        configType: String,
        action: String,
        details: Map<String, Any>,
        operator: String = "SYSTEM_MONITOR"
    ): Mono<GatewayConfigHistoryEntity> {
        return Mono.fromCallable {
            GatewayConfigHistoryEntity(
                configType = configType, configId = configId,
                snapshot = try {
                    objectMapper.writeValueAsString(details)
                } catch (_: Exception) {
                    "{}"
                },
                operator = operator, description = action, createTime = LocalDateTime.now()
            )
        }.flatMap { historyRepository.save(it) }
    }

    private fun triggerAutoAudit(routeId: String, path: String, status: Int, reason: String) {
        val details =
            mapOf("path" to path, "status" to status, "time" to LocalDateTime.now().toString(), "msg" to reason)
        recordSystemAudit(routeId, "SYSTEM_AUTO_MONITOR", reason, details).subscribe()
    }
}