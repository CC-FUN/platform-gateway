package cn.icofun.gateway.filter

import cn.icofun.gateway.model.entity.GatewayAccessLogEntity
import cn.icofun.gateway.plugin.DataMaskingPlugin
import cn.icofun.gateway.service.AccessLogService
import cn.icofun.gateway.service.MonitorService
import cn.icofun.gateway.utils.IpUtils
import cn.icofun.gateway.utils.MdcUtils
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.net.URI
import java.time.LocalDateTime

@Component
class MonitorGlobalFilter(
    private val monitorService: MonitorService,
    private val accessLogService: AccessLogService
) : GlobalFilter, Ordered {
    private val log = LoggerFactory.getLogger(this::class.java)

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
        val routeId = getCleanRouteId(route)

        return monitorService.getRouteStatus(routeId)
            .flatMap { status ->
                when (status) {
                    MonitorService.STATUS_OPEN -> {
                        // 全量熔断状态，直接返回 503
                        returnError(exchange, 503, "Service Circuit Breaker Opened")
                    }
                    MonitorService.STATUS_HALF_OPEN -> {
                        // 半开启状态：仅 5% 概率放行
                        if (java.util.Random().nextInt(100) < 5) {
                            log.info("Half-Open Probe: Allowing request to {}", routeId)
                            val startTime = System.currentTimeMillis()
                            val traceId = exchange.request.headers.getFirst("X-Trace-Id") ?: ""

                            chain.filter(exchange)
                                .doOnSuccess {
                                    // 核心逻辑：如果探测请求成功(非5xx)，尝试彻底恢复
                                    if (exchange.response.statusCode?.is5xxServerError == false) {
                                        monitorService.tryCloseCircuit(routeId).subscribe()
                                    }
                                    record(exchange, startTime, traceId, null)
                                }
                        } else {
                            // 未中签的流量依然拦截
                            returnError(exchange, 503, "Service Under Recovery Probing")
                        }
                    }
                    else -> {
                        // CLOSED 状态正常放行
                        val startTime = System.currentTimeMillis()
                        val traceId = exchange.request.headers.getFirst("X-Trace-Id") ?: ""
                        chain.filter(exchange)
                            .doOnSuccess { record(exchange, startTime, traceId, null) }
                            .doOnError { record(exchange, startTime, traceId, it) }
                    }
                }
            }
    }


    private fun record(
        exchange: ServerWebExchange,
        startTime: Long,
        traceId: String,
        e: Throwable?
    ) {
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        val request = exchange.request
        val path = request.path.value()
        val method = request.method.name()

        var statusCode = exchange.response.statusCode?.value() ?: 200

        if (e != null) {
            statusCode = 500
        }

        // 获取路由信息
        val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
        val routeId = getCleanRouteId(route)
        val targetUri = exchange.getAttribute<URI>(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR)?.toString()

        val clientIp = IpUtils.getClientIp(exchange)

        // 1. 原有的 Redis 统计
        try {
            monitorService.recordMetrics(routeId, path, statusCode, duration)
        } catch (ex: Exception) {
            log.error("Redis metrics error", ex)
        }

        val requestBody = exchange.getAttribute<String>("cachedRequestBodyString")
        val responseBody = exchange.getAttribute<String>(DataMaskingPlugin.MASKED_RESPONSE_ATTR)


        // 2. 构造日志实体并放入缓冲区
        val logEntity = GatewayAccessLogEntity(
            traceId = traceId,
            routeId = routeId,
            requestPath = path,
            requestMethod = method,
            schemaName = request.uri.scheme,
            responseStatus = statusCode,
            clientIp = clientIp,
            duration = duration,
            requestTime = LocalDateTime.now(),
            errorMsg = e?.message,
            targetUri = targetUri,
            requestBody = requestBody,
            responseBody = responseBody
        )

        accessLogService.saveLog(logEntity)
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE - 100

    private fun returnError(exchange: ServerWebExchange, code: Int, message: String): Mono<Void> {
        exchange.response.statusCode = org.springframework.http.HttpStatus.valueOf(code)
        exchange.response.headers.contentType = org.springframework.http.MediaType.APPLICATION_JSON
        val body = "{\"code\": $code, \"message\": \"$message\"}"
        return exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(body.toByteArray())))
    }

    private fun getCleanRouteId(route: Route?): String {
        val rawId = route?.id ?: "unknown"
        // 如果是自动生成的发现服务路由，去掉前缀
        if (rawId.startsWith("ReactiveCompositeDiscoveryClient_")) {
            return rawId.replace("ReactiveCompositeDiscoveryClient_", "")
        }
        return rawId
    }
}