package cn.icofun.gateway.runtime.traffic

import cn.icofun.gateway.core.context.GatewayContext
import cn.icofun.gateway.core.engine.PluginChain
import cn.icofun.gateway.core.spi.GatewayPlugin
import cn.icofun.gateway.infra.i18n.I18nMessageUtils
import cn.icofun.gateway.infra.model.StandardApiResponse
import cn.icofun.gateway.infra.utils.MdcUtils
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class CircuitBreakerPlugin(
    private val rateLimiterRegistry: RateLimiterRegistry,
    private val i18nMessageUtils: I18nMessageUtils,
    private val objectMapper: ObjectMapper,
) : GatewayPlugin {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun getName() = "Resilience4jRateLimiter"
    override fun getOrder() = 2

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val exchange = context.exchange
        val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
        val routeId = route?.id ?: "default"
        val metadata = route?.metadata ?: emptyMap()

        val enabled = when (val value = metadata["resilience_enabled"]) {
            is Boolean -> value
            is String -> value.toBoolean()
            else -> false
        }
        if (!enabled) {
            return chain.execute(context)
        }

        val limitForPeriod = when (val value = metadata["resilience_rate"]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 10
            else -> 10
        }

        val rateLimiter = rateLimiterRegistry.rateLimiter(routeId) {
            RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .limitForPeriod(limitForPeriod)
                .timeoutDuration(Duration.ofMillis(500))
                .build()
        }

        rateLimiter.changeLimitForPeriod(limitForPeriod)

        return try {
            if (rateLimiter.acquirePermission()) {
                chain.execute(context)
            } else {
                handleRateLimitError(context, routeId)
            }
        } catch (_: RequestNotPermitted) {
            handleRateLimitError(context, routeId)
        }
    }

    private fun handleRateLimitError(context: GatewayContext, limiterName: String): Mono<Void> {
        logger.warn("Resilience4j 限流触发: $limiterName")
        val response = context.exchange.response
        response.statusCode = HttpStatus.TOO_MANY_REQUESTS
        response.headers.contentType = MediaType.APPLICATION_JSON

        val msg = i18nMessageUtils.getMessage("error.rate.limit", null, context.exchange.request)

        val responseBody = StandardApiResponse.fail<Any>(429, msg).apply {
            traceId = MdcUtils.getTraceIdOrDefault()
        }

        val bytes = objectMapper.writeValueAsBytes(responseBody)

        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)))
    }
}