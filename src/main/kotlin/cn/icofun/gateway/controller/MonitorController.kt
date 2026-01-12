package cn.icofun.gateway.controller

import cn.icofun.gateway.annotation.LogOperation
import cn.icofun.gateway.model.StandardApiResponse
import cn.icofun.gateway.model.entity.GatewayConfigHistoryEntity
import cn.icofun.gateway.model.entity.GatewayLimitRuleEntity
import cn.icofun.gateway.repository.GatewayConfigHistoryRepository
import cn.icofun.gateway.repository.GatewayLimitRuleRepository
import cn.icofun.gateway.service.MonitorService
import org.springframework.cloud.client.ServiceInstance
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/actuator/gateway/monitor")
class MonitorController(
    private val monitorService: MonitorService,
    private val discoveryClient: ReactiveDiscoveryClient,
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val historyRepository: GatewayConfigHistoryRepository,
    private val limitRuleRepository: GatewayLimitRuleRepository
) {

    @LogOperation(module = "monitor", description = "Get Dashboard Stats")
    @GetMapping("stats")
    fun getDashboardStats(): Mono<StandardApiResponse<Map<String, Any>>> {
        return monitorService.getDashboardStats()
            .map { StandardApiResponse.success(it) }
    }

    /**
     * 获取特定时刻前后的 QPS 趋势数据 (用于审计关联图表)
     * @param timestamp 审计记录的秒级时间戳
     */
    @GetMapping("/trend-at")
    fun getTrendAt(
        @RequestParam timestamp: Long,
        @RequestParam(defaultValue = "120") range: Int // 默认前后各查2分钟
    ): Mono<StandardApiResponse<List<Map<String, Any>>>> {
        val start = timestamp - range
        val end = timestamp + range
        val keys = (start..end).map { "gateway:qps:$it" }

        return redisTemplate.opsForValue().multiGet(keys)
            .map { values ->
                val result = values.mapIndexed { index, value ->
                    mapOf(
                        "time" to (start + index),
                        "value" to (value.toLong())
                    )
                }
                StandardApiResponse.success(result)
            }
    }

    /**
     * 获取系统自动触发的审计历史 (operator = SYSTEM_MONITOR)
     * 用于前端展示“系统自动发现的事故记录”
     */
    @GetMapping("/system-history")
    fun getSystemAuditHistory(): Mono<StandardApiResponse<List<GatewayConfigHistoryEntity>>> {
        return historyRepository.findAll()
            .filter { it.operator == "SYSTEM_MONITOR" }
            .collectList()
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "monitor", description = "Get Traffic Trend")
    @GetMapping("/trend")
    fun getTrafficTrend(): Mono<StandardApiResponse<List<Int>>> {
        return monitorService.getTrendData()
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "monitor", description = "Get Service Rankings")
    @GetMapping("/services")
    fun getServiceRankings(): Mono<StandardApiResponse<List<Map<String, Any>>>> {
        return monitorService.getServiceRankings()
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "monitor", description = "Get Service Instances")
    @GetMapping("/instances")
    fun getServiceInstances(): Mono<StandardApiResponse<Map<String, List<ServiceInstance>>>> {        return discoveryClient.services
            .flatMap { service ->
                discoveryClient.getInstances(service)
                    .collectList()
                    .map { instances -> service to instances }
            }
            .collectMap({ it.first }, { it.second })
            .map { StandardApiResponse.success(it) }
    }

    @GetMapping("/services/list")
    fun getActiveServices(): Mono<StandardApiResponse<List<String>>> {
        return discoveryClient.services
            .collectList()
            .map { StandardApiResponse.success(it) }
    }

    /**
     * 手动切换路由管控状态（熔断/恢复）
     */
    @PostMapping("/route/{routeId}/status")
    fun updateRouteStatus(
        @PathVariable routeId: String,
        @RequestParam status: String, // "NORMAL" or "SUSPENDED"
        @RequestParam(defaultValue = "Manual control by admin") reason: String
    ): Mono<StandardApiResponse<Boolean>> {
        // 调用 Service 写入 Redis 并记录审计流水
        return monitorService.setRouteStatus(routeId, status, "admin", reason)
            .map { StandardApiResponse.success(it) }
    }


    /**
     * 获取指定路由列表的状态
     * 前端刷新 Dashboard 时，通过此接口获知哪个开关该打开，哪个该关闭
     */
    @GetMapping("/route/statuses")
    fun getRouteStatuses(@RequestParam routeIds: List<String>): Mono<StandardApiResponse<Map<String, String>>> {
        return Flux.fromIterable(routeIds)
            .flatMap { id -> monitorService.getRouteStatus(id).map { id to it } }
            .collectMap({ it.first }, { it.second })
            .map { StandardApiResponse.success(it) }
    }

    @PostMapping("/limit-rule")
    fun saveLimitRule(@RequestBody rule: GatewayLimitRuleEntity): Mono<StandardApiResponse<GatewayLimitRuleEntity>> {
        return limitRuleRepository.save(rule)
            .flatMap { saved ->
                // 同时更新 Redis 缓存，格式: gateway:ratelimit:config:IP:192.168.1.1
                val cacheKey = "gateway:ratelimit:config:${saved.limitKey}:${saved.limitValue}"
                redisTemplate.opsForValue().set(cacheKey, saved.qpsThreshold.toString())
                    .thenReturn(StandardApiResponse.success(saved))
            }
    }

    /**
     * 获取所有限流规则
     */
    @GetMapping("/limit-rules")
    fun getLimitRules(): Mono<StandardApiResponse<List<GatewayLimitRuleEntity>>> {
        return limitRuleRepository.findAll().collectList().map { StandardApiResponse.success(it) }
    }

}