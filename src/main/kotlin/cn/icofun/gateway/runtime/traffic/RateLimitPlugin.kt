package cn.icofun.gateway.runtime.traffic

import cn.icofun.gateway.core.context.GatewayContext
import cn.icofun.gateway.core.engine.PluginChain
import cn.icofun.gateway.core.spi.GatewayPlugin
import cn.icofun.gateway.infra.i18n.I18nMessageUtils
import cn.icofun.gateway.infra.model.StandardApiResponse
import cn.icofun.gateway.infra.service.GatewayConfigService
import cn.icofun.gateway.admin.monitor.service.MonitorService
import cn.icofun.gateway.infra.utils.MdcUtils
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.time.LocalDateTime

@Component
class RateLimitPlugin(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val i18nMessageUtils: I18nMessageUtils,
    private val objectMapper: ObjectMapper,
    private val monitorService: MonitorService,
    private val gatewayConfigService: GatewayConfigService
) : GatewayPlugin {
    private val log = LoggerFactory.getLogger(this::class.java)

    companion object {
        const val TENANT_CONTEXT_KEY = "GATEWAY_TENANT_ID"
    }

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
        val exchange = context.exchange
        val request = exchange.request
        val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)

        val ip = request.remoteAddress?.address?.hostAddress?.replace(":", ".") ?: "unknown"
        val appId = request.headers.getFirst("X-App-Id") ?: "guest"
        val path = request.path.value()

        val tenantId = exchange.getAttribute<String>(TENANT_CONTEXT_KEY) ?: "default"

        return checkFineGrainedLimit("TENANT", tenantId)
            .switchIfEmpty(checkFineGrainedLimit("APP_ID", appId))
            .switchIfEmpty(checkFineGrainedLimit("IP", ip))
            .switchIfEmpty(checkFineGrainedLimit("PATH", path))
            .flatMap { threshold ->
                // 执行精细化限流 Redis 计数
                val key = "rate_limit:fine:$tenantId:$path:$ip"
                redisTemplate.execute(script, listOf(key), listOf(threshold.toString(), "60"))
                    .next()
                    .flatMap { result ->
                        if (result == 1L) {
                            chain.execute(context)
                        } else {
                            triggerLimitAudit(tenantId, ip, path, threshold, "Tenant/Fine-Grained Limit")
                                .then(failResponse(context, "Rate limit exceeded for Tenant: $tenantId"))
                        }
                    }
            }
            // 优先级 2: 如果没有精细化配置，降级到原有的路由 Metadata 默认限流逻辑
            .switchIfEmpty(handleDefaultRouteLimit(context, chain, route, ip, path))
    }


    private fun checkFineGrainedLimit(key: String, value: String): Mono<Int> {
        return gatewayConfigService.getLimitThreshold(key, value)
    }

    private fun handleDefaultRouteLimit(
        context: GatewayContext,
        chain: PluginChain,
        route: Route?,
        ip: String,
        path: String
    ): Mono<Void> {
        val metadata = route?.metadata ?: emptyMap()
        val limitObj = metadata["rate_limit"]
        val expireObj = metadata["rate_expire"]

        // 如果路由也没配限流，直接放行
        if (limitObj == null || expireObj == null) {
            return chain.execute(context)
        }

        val limit = limitObj.toString().toLongOrNull() ?: 50L
        val expire = expireObj.toString().toLongOrNull() ?: 60L
        val key = "rate_limit:default:${route?.id}:${ip}_${path}"

        return redisTemplate.execute(script, listOf(key), listOf(limit.toString(), expire.toString()))
            .next()
            .flatMap { result ->
                if (result == 1L) {
                    chain.execute(context)
                } else {
                    triggerLimitAudit("N/A", ip, path, limit.toInt(), "Route Default Limit")
                        .then(failResponse(context, "Route default rate limit exceeded for $ip"))
                }
            }
    }

    /**
     * 触发审计逻辑：将限流事件记录到数据库
     */
    private fun triggerLimitAudit(appId: String, ip: String, path: String, threshold: Int, type: String): Mono<Void> {
        val details = mapOf(
            "appId" to appId,
            "ip" to ip,
            "path" to path,
            "threshold" to threshold,
            "limitType" to type,
            "timestamp" to LocalDateTime.now().toString()
        )
        // 调用 MonitorService 记录到 gateway_config_history 表
        return monitorService.recordSystemAudit(
            configId = if (appId != "guest") appId else ip,
            configType = "RATE_LIMIT",
            action = "Request Throttled",
            details = details
        ).then()
    }

    private fun failResponse(context: GatewayContext, logMsg: String): Mono<Void> {
        log.warn(logMsg)
        val response = context.exchange.response
        response.statusCode = HttpStatus.TOO_MANY_REQUESTS
        response.headers.contentType = MediaType.APPLICATION_JSON

        val msg = i18nMessageUtils.getMessage("error.rate.limit", null, context.exchange.request)
        val responseBody = StandardApiResponse.fail<Any>(429, msg).apply {
            traceId = MdcUtils.getTraceIdOrDefault()
        }

        val bytes = objectMapper.writeValueAsBytes(responseBody)
        val buffer = response.bufferFactory().wrap(bytes)
        return response.writeWith(Mono.just(buffer))
    }
}