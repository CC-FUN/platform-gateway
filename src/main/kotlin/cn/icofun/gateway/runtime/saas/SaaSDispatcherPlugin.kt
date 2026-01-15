package cn.icofun.gateway.runtime.saas

import cn.icofun.gateway.core.context.GatewayContext
import cn.icofun.gateway.core.context.SaaSContext
import cn.icofun.gateway.core.engine.PluginChain
import cn.icofun.gateway.core.spi.GatewayPlugin
import cn.icofun.gateway.infra.config.SaaSConfig
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono

/**
 * 双模分发器
 * 模式 A (Async): 存在 bizType -> 拦截请求 -> 包装 Event -> 投递 SaaS Ingress -> 返回 202
 * 模式 B (Sync): 无 bizType -> 放行 -> 走 Spring Cloud Gateway 原生路由 -> 微服务
 */
@Component
class SaaSDispatcherPlugin(
    private val webClientBuilder: WebClient.Builder,
    private val saasConfig: SaaSConfig
) : GatewayPlugin {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun getName() = "SaaSDispatcher"
    // 在限流、鉴权之后执行
    override fun getOrder() = 1000

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val exchange = context.exchange
        val saasContext = exchange.attributes[SaaSContext.KEY] as? SaaSContext
            ?: return chain.execute(context) // 防御性编程

        // === 判断模式 ===
        if (saasContext.bizType.isNullOrBlank()) {
            // [模式 B] 同步直连：直接放行
            return chain.execute(context)
        }

        // === [模式 A] 异步分发 ===
        if (logger.isDebugEnabled) {
            logger.debug("⚡ Async Dispatch: [${saasContext.bizType}] Tenant: [${saasContext.tenantId}]")
        }

        // 获取请求体 (依赖 GlobalBodyCachingFilter)
        // 注意：Body 必须已经在之前的 Filter 中被缓存为 String，否则这里是空的
        val payload = exchange.attributes["cachedRequestBodyString"] as? String ?: "{}"

        // 构造投递给 Ingress 的 Event 包
        // 这里简单用 Map，实际建议用 DTO
        val eventPackage = mapOf(
            "tenantId" to saasContext.tenantId,
            "bizType" to saasContext.bizType,
            "traceId" to saasContext.traceId,
            "payload" to payload,
            "timestamp" to System.currentTimeMillis()
        )

        // 异步投递
        return webClientBuilder.build()
            .post()
            .uri("${saasConfig.ingressUrl}/internal/ingest")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(eventPackage)
            .retrieve()
            .bodyToMono<String>()
                .flatMap {
                    // 投递成功，直接响应前端 202 Accepted
                    writeResponse(exchange, 202, """{"code": 202, "message": "Request Accepted", "traceId": "${saasContext.traceId}"}""")
                }
                .onErrorResume { e ->
                    // 投递失败，降级处理或报错
                    logger.error("🔥 SaaS Ingress Dispatch Failed: ${e.message}", e)
                    writeResponse(exchange, 503, """{"code": 503, "message": "SaaS Service Unavailable"}""")
                }
        // 注意：这里没有调用 chain.execute，所以请求被"劫持"了，不会再去请求下游微服务
    }

    private fun writeResponse(exchange: org.springframework.web.server.ServerWebExchange, status: Int, json: String): Mono<Void> {
        exchange.response.statusCode = org.springframework.http.HttpStatus.valueOf(status)
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        val bytes = json.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        return exchange.response.writeWith(Mono.just(exchange.response.bufferFactory().wrap(bytes)))
    }
}