package cn.icofun.gateway.filter

import cn.icofun.gateway.service.MonitorService
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class MonitorGlobalFilter(
    private val monitorService: MonitorService
) : GlobalFilter, Ordered {
    private val log = LoggerFactory.getLogger(this::class.java)

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val startTime = System.currentTimeMillis()
        log.info("MonitorFilter: Request entered: {}", exchange.request.path)
        return chain.filter(exchange)
            .doOnSuccess {
                record(exchange, startTime, null)
            }
            .doOnError {
                record(exchange, startTime, it)
            }
    }

    private fun record(
        exchange: ServerWebExchange,
        startTime: Long,
        e: Throwable?
    ) {
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        val path = exchange.request.path.value()

        var statusCode = exchange.response.statusCode?.value() ?: 200

        val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
        val routeId = route?.id ?: "unknown"

        if (e != null) {
            if (statusCode == 200) {
                statusCode = 500
            }
            log.warn("MonitorFilter: Request Error/Blocked. Path: $path, Error: ${e.message}")
        }
        log.info("MonitorFilter: Recording -> Path: $path, Status: $statusCode, Time: ${duration}ms")

        try {
            monitorService.recordMetrics(routeId, path, statusCode, duration)
        } catch (e: Exception) {
            log.error("MonitorService record failed", e)
        }
    }

    override fun getOrder(): Int {
        return Ordered.HIGHEST_PRECEDENCE
    }
}