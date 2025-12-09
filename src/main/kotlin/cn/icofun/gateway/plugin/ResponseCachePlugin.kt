package cn.icofun.gateway.plugin

import cn.icofun.gateway.core.GatewayContext
import cn.icofun.gateway.core.GatewayPlugin
import cn.icofun.gateway.core.PluginChain
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class ResponseCachePlugin(
    private val redisTemplate: ReactiveRedisTemplate<String, String>,
) : GatewayPlugin {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override fun getName() = "ResponseCache"
    override fun getOrder() = -50

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val request = context.exchange.request

        if (request.method != HttpMethod.GET) {
            return chain.execute(context)
        }
        val path = request.path.value()
        val cacheKey = "gateway:response:$path"

        return redisTemplate.opsForValue().get(cacheKey)
            .flatMap { cachedJson ->
                logger.debug("✅ 命中缓存: $cacheKey")
                val response = context.exchange.response
                response.headers.contentType = MediaType.APPLICATION_JSON
                response.headers.add("X-Cache-Status", "HIT")

                val buffer = response.bufferFactory().wrap(cachedJson.toByteArray())
                response.writeWith(Mono.just(buffer))
            }
            .switchIfEmpty(
                chain.execute(context)
            )
    }
}