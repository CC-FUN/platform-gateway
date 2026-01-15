package cn.icofun.gateway.runtime.transform

import cn.icofun.gateway.core.context.GatewayContext
import cn.icofun.gateway.core.engine.PluginChain
import cn.icofun.gateway.core.spi.GatewayPlugin
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets

@Component
class ResponseCachePlugin(
    private val redisTemplate: ReactiveRedisTemplate<String, String>,
) : GatewayPlugin {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override fun getName() = "ResponseCache"
    override fun getOrder() = -50
    override fun isCritical() = false

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val exchange = context.exchange
        val request = exchange.request

        if (request.method != HttpMethod.GET) {
            return chain.execute(context)
        }

        val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
        val metadata = route?.metadata ?: emptyMap()
        val enabled = when (val value = metadata["cache_enabled"]) {
            is Boolean -> value
            is String -> value.toBoolean()
            else -> false
        }

        if (!enabled) {
            return chain.execute(context)
        }

        val path = request.path.value()
        val query = request.queryParams.toString()
        val cacheKey = "gateway:response:${route?.id}:${path}:${query.hashCode()}"

        return redisTemplate.opsForValue().get(cacheKey)
            .flatMap { cachedJson ->
                logger.debug("✅ Cache HIT: {}", cacheKey)
                val response = context.exchange.response
                response.headers.contentType = MediaType.APPLICATION_JSON
                response.headers.add("X-Cache-Status", "HIT")

                val buffer = response.bufferFactory().wrap(cachedJson.toByteArray(StandardCharsets.UTF_8))
                response.writeWith(Mono.just(buffer))
            }
            .onErrorResume { e ->
                logger.warn("Redis cache read failed: ${e.message}")
                chain.execute(context)
            }
            .switchIfEmpty(
                chain.execute(context)
            )
    }
}