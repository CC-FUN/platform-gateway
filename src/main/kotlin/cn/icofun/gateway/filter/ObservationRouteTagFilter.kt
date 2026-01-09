package cn.icofun.gateway.filter

import io.micrometer.observation.Observation
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * 链路追踪与监控标签注入过滤器 (Spring Boot 4.0 / WebFlux)
 * 替代 ObservationFilter，在网关过滤器链中直接向 Observation 注入 route_id 和 tenant_id
 */
@Component
class ObservationRouteTagFilter : GlobalFilter, Ordered {

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        return chain.filter(exchange).doOnSuccess {
            // 1. 获取当前请求关联的 Observation (由 ServerHttpObservationFilter 创建并挂载在 Attribute 中)
            val observation = exchange.getAttribute<Observation>(Observation::class.java.name)

            if (observation != null) {
                // 2. 获取 Gateway 路由对象 (此时路由已匹配完成)
                val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
                val routeId = route?.id ?: "unknown"

                // 3. 获取租户 ID
                val tenantId = exchange.getAttribute<String>("GATEWAY_TENANT_ID") ?: "default"

                // 4. 注入 LowCardinality KeyValue (会被 Prometheus 索引为 Tag，也会出现在 Zipkin/Trace 中)
                observation.lowCardinalityKeyValue("route_id", routeId)
                observation.lowCardinalityKeyValue("tenant_id", tenantId)
            }
        }
    }

    // 必须在路由匹配之后执行，建议设为最低优先级，确保路由信息已存在，且请求处理完成(doOnSuccess)
    override fun getOrder(): Int {
        return Ordered.LOWEST_PRECEDENCE
    }
}