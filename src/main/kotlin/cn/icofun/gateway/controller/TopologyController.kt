package cn.icofun.gateway.controller

import cn.icofun.gateway.model.StandardApiResponse
import cn.icofun.gateway.repository.GatewayRouteRepository
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.time.Instant

@RestController
@RequestMapping("/actuator/gateway/topology")
class TopologyController(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val routeRepository: GatewayRouteRepository
) {

    data class TopologyNode(val id: String, val type: String, val name: String, val status: String, val qps: Long = 0)
    data class TopologyEdge(val source: String, val target: String, val value: Long, val label: String)
    data class TopologyGraph(val nodes: List<TopologyNode>, val edges: List<TopologyEdge>)

    @GetMapping
    fun getLiveTopology(): Mono<StandardApiResponse<TopologyGraph>> {
        val now = Instant.now().epochSecond
        val lastSecond = now - 1

        val qpsKey = "gateway:metric:route_qps:v2:$lastSecond"
        val currentKey = "gateway:metric:route_qps:v2:$now"


        return Mono.zip(
            routeRepository.findAll().collectList(),
            // 尝试获取上一秒数据，如果为空则获取当前秒数据
            redisTemplate.opsForHash<String, String>().entries(qpsKey).collectMap({ it.key }, { it.value })
                .flatMap { map ->
                    if (map.isEmpty()) redisTemplate.opsForHash<String, String>().entries(currentKey).collectMap({ it.key }, { it.value })
                    else Mono.just(map)
                }
        ).map { tuple ->
            val routes = tuple.t1
            val qpsMap = tuple.t2

            val nodes = mutableListOf<TopologyNode>()
            val edges = mutableListOf<TopologyEdge>()

            // 【修改】将 ID 改为 "gateway" 以匹配前端 JSON 需求
            nodes.add(TopologyNode("gateway", "GATEWAY", "API 网关中心", "UP"))

            routes.forEach { route ->
                val cleanId = if (route.id.startsWith("ReactiveCompositeDiscoveryClient_")) {
                    route.id.replace("ReactiveCompositeDiscoveryClient_", "")
                } else {
                    route.id
                }

                // 转换数值，确保不是 NaN
                val qps = qpsMap[cleanId]?.toLongOrNull() ?: 0L

                nodes.add(TopologyNode(
                    id = cleanId,
                    type = "SERVICE",
                    name = route.description ?: cleanId,
                    status = if (route.enabled) "UP" else "DOWN",
                    qps = qps
                ))

                // 【修改】source 必须与上面的 gateway 节点 ID 一致
                edges.add(TopologyEdge(
                    source = "gateway",
                    target = cleanId,
                    value = qps,
                    label = "QPS: $qps"
                ))
            }

            StandardApiResponse.success(TopologyGraph(nodes, edges))
        }
    }


    /**
     * 获取 60 秒全局 QPS 趋势图
     */
    @GetMapping("/metrics/qps")
    fun getGlobalQpsTrend(): Mono<StandardApiResponse<List<Long>>> {
        val now = Instant.now().epochSecond
        val keys = (0..59).map { i -> "gateway:metric:global:qps:${now - 59 + i}" }

        return redisTemplate.opsForValue().multiGet(keys)
            .map { list ->
                val data = list.map { it?.toLongOrNull() ?: 0L }
                StandardApiResponse.success(data)
            }
    }
}