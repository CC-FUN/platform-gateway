package cn.icofun.gateway.admin.monitor.controller

import cn.icofun.gateway.admin.monitor.repository.GatewayAttackLogRepository
import cn.icofun.gateway.admin.route.service.DynamicRouteService
import cn.icofun.gateway.admin.monitor.service.MonitorService
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.time.LocalDateTime

@RestController
@RequestMapping("/actuator/gateway/monitor")
class GatewayMonitorController(
    private val monitorService: MonitorService,
    private val dynamicRouteService: DynamicRouteService,
    private val attackLogRepository: GatewayAttackLogRepository
) {

    /**
     * 获取 WAF 防护指标 (模拟数据，请根据实际业务替换)
     * 对应前端: getWafMetrics
     */
    @GetMapping("/waf/metrics")
    fun getWafMetrics(): Mono<Map<String, Any>> {
        val totalRequestsMono = monitorService.getDashboardStats()
            .map { it["total"] as? Long ?: 0L }
            .defaultIfEmpty(0L)

        // 2. 获取总拦截数 (从数据库统计)
        val blockedRequestsMono = attackLogRepository.count()

        // 3. 获取最近一次攻击时间
        val lastAttackMono = attackLogRepository.findFirstByOrderByAttackTimeDesc()
            .map { it.attackTime }
            .defaultIfEmpty(LocalDateTime.MIN)

        // 4. (可选) 获取攻击类型分布
        // 由于 R2DBC 对聚合查询支持较弱，这里为了性能暂取最近 100 条做简单的内存聚合分析，或者直接留空
        // 生产环境建议通过 @Query 自定义 SQL GROUP BY 实现
        val attackDistributionMono = attackLogRepository.findByCondition(
            null, null, LocalDateTime.now().minusDays(1), LocalDateTime.now(), 100, 0
        ).collectList().map { list ->
            list.groupingBy { it.attackType }.eachCount()
        }.defaultIfEmpty(emptyMap())

        return Mono.zip(totalRequestsMono, blockedRequestsMono, lastAttackMono, attackDistributionMono)
            .map { tuple ->
                val total = tuple.t1
                val blocked = tuple.t2
                val lastTime = if (tuple.t3 == LocalDateTime.MIN) "" else tuple.t3.toString()
                val distribution = tuple.t4

                mapOf(
                    "totalRequests" to total,
                    "blockedRequests" to blocked,
                    "attackTypes" to distribution,
                    "lastAttackTime" to lastTime,
                    "status" to "ACTIVE" // WAF 插件状态，这里假设开启，后续可读取配置
                )
            }
    }

    /**
     * 获取服务拓扑图数据 (模拟数据)
     * 对应前端: getTopology
     */
    @GetMapping("/topology")
    fun getTopology(): Mono<Map<String, Any>> {
        // 1. 获取所有路由定义 (用于构建节点关系)
        val routesMono = dynamicRouteService.getAll().collectList()

        val qpsMono = monitorService.getCurrentRouteQps()

        val statsMono = monitorService.getServiceRankings()
            .map { list -> list.associateBy { it["name"] as String } }

        return Mono.zip(routesMono, qpsMono, statsMono).map { tuple ->
            val routeList = tuple.t1
            val qpsMap = tuple.t2
            val statsMap = tuple.t3

            val nodes = mutableListOf<Map<String, Any>>()
            val edges = mutableListOf<Map<String, Any>>()

            nodes.add(mapOf("id" to "gateway", "name" to "API Gateway", "type" to "GATEWAY"))

            val serviceNodes = mutableSetOf<String>()

            routeList.forEach { route ->
                val cleanId = if (route.id.startsWith("ReactiveCompositeDiscoveryClient_")) {
                    route.id.replace("ReactiveCompositeDiscoveryClient_", "")
                } else {
                    route.id
                }

                val currentQps = qpsMap[cleanId] ?: 0.0
                val routeStats = statsMap[cleanId]
                val totalRequests = routeStats?.get("total") as? Long ?: 0L
                val errorCount = routeStats?.get("error") as? Long ?: 0L

                if (serviceNodes.add(cleanId)) {
                    nodes.add(
                        mapOf(
                            "id" to cleanId,
                            "name" to (route.description ?: cleanId),
                            "type" to "SERVICE"
                        )
                    )
                }

                // 添加连线
                edges.add(
                    mapOf(
                        "source" to "gateway",
                        "target" to cleanId,
                        "value" to currentQps, // 使用实时 QPS 控制线条粗细
                        // 标签显示：实时 QPS 和 今日总量
                        "label" to "QPS: %.1f\nTotal: %d".format(currentQps, totalRequests),
                        "status" to if (errorCount > 0) "WARN" else "OK"
                    )
                )
            }

            mapOf(
                "nodes" to nodes,
                "edges" to edges
            )
        }
    }
}