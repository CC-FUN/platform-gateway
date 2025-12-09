package cn.icofun.gateway.plugin

import cn.icofun.gateway.core.GatewayContext
import cn.icofun.gateway.core.GatewayPlugin
import cn.icofun.gateway.core.PluginChain
import cn.icofun.gateway.i18n.I18nMessageUtils
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class RedisLimitPlugin(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val i18nMessageUtils: I18nMessageUtils,
    private val objectMapper: ObjectMapper
) : GatewayPlugin {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val script = DefaultRedisScript<Long>().apply {
        setScriptText(
            """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local expire = tonumber(ARGV[2])
            local current = tonumber(redis.call('get', key) or '0')
            if current + 1 > limit then
                return 0
            else
                redis.call('incr', key)
                if current == 0 then
                    redis.call('expire', key, expire)
                end
                return 1
            end
        """.trimIndent()
        )
        resultType = Long::class.java
    }

    override fun getName() = "RedisRateLimiter"
    override fun getOrder() = 1


    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val request = context.exchange.request
        val ip = request.remoteAddress?.address?.hostAddress?.replace(":", ".")
        val path = request.path.value()
        val key = "rate_limit_${ip}_${path}"
        val limit = 50
        val expire = 60

        return redisTemplate.execute(script, listOf(key), listOf(limit.toString(), expire.toString()))
            .next()
            .flatMap { result ->
                if (result == 1L) {
                    chain.execute(context)
                } else {
                    log.warn("Rate limit exceeded for $ip")
                    val response = context.exchange.response
                    response.statusCode = HttpStatus.TOO_MANY_REQUESTS
                    response.headers.contentType = MediaType.APPLICATION_JSON
                    val msg = i18nMessageUtils.getMessage("error.rate.limit", request, "Too Many Requests")
                    val body = mapOf(
                        "code" to 429,
                        "message" to msg,
                        "data" to null
                    )
                    val bytes = objectMapper.writeValueAsBytes(body)
                    val buffer = response.bufferFactory().wrap(bytes)
                    response.writeWith(Mono.just(buffer))
                    // 注意：这里不调用 chain.execute，直接返回，中断链条
                }
            }
    }
}