package cn.icofun.gateway.service

import cn.icofun.gateway.model.MockHttpRequest
import cn.icofun.gateway.model.RouteSimulationResult
import cn.icofun.gateway.model.dto.GatewayRouteDTO
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpMethod
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.stereotype.Service
import org.springframework.web.server.ServerWebExchange
import java.util.function.Predicate

@Service
class RouteSimulatorService(
    // applicationContext 如果确实没用到可以删除，或者保留以备扩展
    private val applicationContext: ApplicationContext,
    private val objectMapper: ObjectMapper,
    private val predicateFactories: List<RoutePredicateFactory<*>>
) {

    fun simulate(routeDto: GatewayRouteDTO, mockReq: MockHttpRequest): RouteSimulationResult {
        // 1. 构建 Mock Exchange (现在会被使用了)
        val requestBuilder = MockServerHttpRequest
            .method(HttpMethod.valueOf(mockReq.method.uppercase()), mockReq.uri)
            .remoteAddress(java.net.InetSocketAddress(mockReq.remoteIp, 80))

        mockReq.headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        mockReq.queryParams.forEach { (k, v) -> requestBuilder.queryParam(k, v) } // 支持 Query 参数

        val exchange = MockServerWebExchange.from(requestBuilder.build())

        try {
            // 2. 遍历并执行真实的 Predicate 测试
            val predicates = routeDto.predicates
            if (predicates.isEmpty()) {
                return RouteSimulationResult(false, "No predicates defined", emptyList())
            }

            for (predDef in predicates) {
                // 2.1 查找工厂
                val factory = predicateFactories.find { it.name() == predDef.name }
                    ?: return RouteSimulationResult(false, "Unknown predicate: ${predDef.name}", emptyList())

                // 2.2 构建配置对象 (利用 unused 的 predicateConfig 和 objectMapper)
                val config = factory.newConfig()
                val args = predDef.args

                // 【核心修复】利用 ObjectMapper 将 Map<String, String> 绑定到 Config 对象
                // Spring Gateway 内部使用的是 ConfigurationPropertiesBinder，这里我们用 Jackson 模拟绑定
                if (!args.isNullOrEmpty()) {
                    try {
                        // 注意：这里是一个简化的绑定，适用于大多数标准 Predicate
                        objectMapper.updateValue(config, args)
                    } catch (e: Exception) {
                        return RouteSimulationResult(false, "Invalid args for ${predDef.name}: ${e.message}", emptyList())
                    }
                }

                // 2.3 生成断言逻辑 (泛型强转处理)
                // unchecked cast 是必要的，因为我们是在运行时动态处理泛型工厂
                @Suppress("UNCHECKED_CAST")
                val typedFactory = factory as RoutePredicateFactory<Any>
                val predicate: Predicate<ServerWebExchange> = typedFactory.apply(config)

                // 2.4 执行测试 (利用 unused 的 exchange)
                if (!predicate.test(exchange)) {
                    // 匹配失败，直接返回
                    return RouteSimulationResult(
                        false,
                        "Predicate mismatch: ${predDef.name} (Args: ${predDef.args})",
                        emptyList()
                    )
                }
            }

            // --- 3. 分析插件 (保持原有逻辑) ---
            val activePlugins = mutableListOf<String>()

            val dlpEnabled = when (val value = routeDto.metadata["dlp_enabled"]) {
                is Boolean -> value
                is String -> value.toBoolean()
                else -> false
            }
            if (dlpEnabled) activePlugins.add("DlpPlugin")
            
            val protocol = when (val value = routeDto.metadata["protocol"]) {
                is String -> value
                else -> null
            }
            if ("grpc".equals(protocol, ignoreCase = true)) activePlugins.add("GrpcTranscodingPlugin")
            
            if (routeDto.metadata.keys.any { it.startsWith("rate_limit") }) activePlugins.add("RedisLimitPlugin")

            routeDto.filters.forEach { filterDef ->
                activePlugins.add("Filter: ${filterDef.name}")
            }

            return RouteSimulationResult(
                matched = true,
                matchDetails = "All predicates matched",
                activePlugins = activePlugins,
                limitRules = routeDto.metadata.filterKeys { it.startsWith("rate_") }
            )

        } catch (e: Exception) {
            return RouteSimulationResult(false, "Simulation Error: ${e.message}", emptyList())
        }
    }
}