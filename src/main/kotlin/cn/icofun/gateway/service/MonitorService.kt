package cn.icofun.gateway.service

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
class MonitorService(
    private val redisTemplate: ReactiveStringRedisTemplate
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    private fun getDateKey() = "gateway:metrics:${LocalDateTime.now().format(DateTimeFormatter.BASIC_ISO_DATE)}"
    private fun getQpsKey(timestamp: Long) = "gateway:qps:$timestamp"

    fun recordMetrics(routeId: String, path: String, status: Int, duration: Long) {
        val dateKey = getDateKey()
        val currentMinute = LocalDateTime.now().hour * 60 + LocalDateTime.now().minute

        redisTemplate.opsForValue().increment("$dateKey:total")
            .doOnError { e ->
                log.error("Redis Error (Total): ${e.message}")
            }.subscribe()

        if (status >= 400) {
            redisTemplate.opsForValue().increment("$dateKey:error")
                .doOnError { e -> log.error("Redis Error (ErrorCount): ${e.message}") }
                .subscribe()
        }

        redisTemplate.opsForHash<String, String>().increment("$dateKey:trend", currentMinute.toString(), 1L)
            .doOnError { e -> log.error("Redis Error (Trend): ${e.message}") }
            .subscribe()

        val nowSeconds = System.currentTimeMillis() / 1000
        val qpsKey = getQpsKey(nowSeconds)
        redisTemplate.opsForValue().increment(qpsKey)
            .flatMap { redisTemplate.expire(qpsKey, Duration.ofSeconds(10)) }
            .doOnError { e -> log.error("Redis Error (QPS): ${e.message}") }
            .subscribe()

        redisTemplate.opsForHash<String, String>().increment("$dateKey:route_req", routeId, 1L).subscribe()

        if (status >= 400) {
            redisTemplate.opsForHash<String, String>().increment("$dateKey:route_err", routeId, 1L).subscribe()
        }
    }

    fun getDashboardStats(): Mono<Map<String, Any>> {
        val dateKey = getDateKey()

        val totalMono = redisTemplate.opsForValue().get("$dateKey:total").defaultIfEmpty("0").map { it.toLong() }
        val errorMono = redisTemplate.opsForValue().get("$dateKey:error").defaultIfEmpty("0").map { it.toLong() }

        val nowSeconds = System.currentTimeMillis() / 1000
        val qpsKeys = (1..5).map { i -> getQpsKey(nowSeconds - i) } // 生成前1秒到前5秒的Key列表
        val qpsMono = redisTemplate.opsForValue().multiGet(qpsKeys)
            .map { list ->
                val sum = list.filterNotNull().sumOf { it.toLong() }
                if (sum > 0) sum / 5 else 0
            }

        val uptime = ManagementFactory.getRuntimeMXBean().uptime / 1000

        return Mono.zip(totalMono, errorMono, qpsMono).map { tuple ->
            val total = tuple.t1
            val error = tuple.t2
            val qps = tuple.t3

            val successRate =
                if (total > 0) String.format("%.2f", (1L - error.toDouble() / total) * 100).toDouble() else 100.0

            mapOf(
                "qps" to qps,
                "total" to total,
                "error" to error,
                "successRate" to successRate,
                "uptime" to uptime
            )
        }
    }

    fun getTrendData(): Mono<List<Int>> {
        val dateKey = getDateKey()

        return redisTemplate.opsForHash<String, String>().entries("$dateKey:trend")
            .collectMap({ it.key.toInt() }, { it.value.toInt() })
            .map { map ->
                List(1440) { i ->
                    map[i] ?: 0
                }
            }
            .defaultIfEmpty(List(1440) { 0 })
    }

    fun getServiceRankings(): Mono<List<Map<String, Any>>> {
        val dateKey = getDateKey()

        val reqMono = redisTemplate.opsForHash<String, String>().entries("$dateKey:route_req")
            .collectMap({ it.key }, { it.value.toLong() })

        val errMono = redisTemplate.opsForHash<String, String>().entries("$dateKey:route_err")
            .collectMap({ it.key }, { it.value.toLong() })

        return Mono.zip(reqMono, errMono).map { tuple ->
            val reqMap = tuple.t1
            val errMap = tuple.t2

            reqMap.map { (routeId, total) ->
                val error = errMap[routeId] ?: 0L
                val rate = if (total > 0) (1L - error.toDouble() / total) * 100 else 100.0

                mapOf<String, Any>(
                    "name" to routeId,       // 服务名
                    "total" to total,        // 请求量
                    "error" to error,        // 错误量
                    "successRate" to String.format("%.2f", rate).toDouble() // 成功率
                )
            }.sortedByDescending { it["total"] as Long }
        }.defaultIfEmpty(emptyList())
    }
}