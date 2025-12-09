package cn.icofun.gateway.plugin

import cn.icofun.gateway.core.GatewayContext
import cn.icofun.gateway.core.GatewayPlugin
import cn.icofun.gateway.core.PluginChain
import cn.icofun.gateway.i18n.I18nMessageUtils
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class ResiliencePlugin(
    private val rateLimiterRegistry: RateLimiterRegistry,
    private val i18nMessageUtils: I18nMessageUtils,
    private val objectMapper: ObjectMapper,
) : GatewayPlugin {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun getName() = "Resilience4jRateLimiter"
    override fun getOrder() = 2

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val limiterName = "default"
        val rateLimiter = rateLimiterRegistry.rateLimiter(limiterName)

        return try {
            if (rateLimiter.acquirePermission()) {
                chain.execute(context)
            } else {
                handleRateLimitError(context, limiterName)
            }
        } catch (_: RequestNotPermitted) {
            handleRateLimitError(context, limiterName)
        }
    }

    private fun handleRateLimitError(context: GatewayContext, limiterName: String): Mono<Void> {
        logger.warn("Resilience4j 限流触发: $limiterName")
        val response = context.exchange.response
        response.statusCode = HttpStatus.TOO_MANY_REQUESTS
        response.headers.contentType = MediaType.APPLICATION_JSON

        val msg = i18nMessageUtils.getMessage(
            "error.rate.limit",
            context.exchange.request,
            "Too Many Requests (Resilience4j)"
        )

        val body = mapOf("code" to 429, "message" to msg)
        val bytes = objectMapper.writeValueAsBytes(body)

        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)))
    }
}