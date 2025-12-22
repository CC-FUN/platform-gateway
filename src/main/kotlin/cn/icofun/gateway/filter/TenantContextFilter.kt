package cn.icofun.gateway.filter

import cn.icofun.gateway.core.GatewayContext
import cn.icofun.gateway.utils.MdcUtils
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * 多租户上下文过滤器
 * 功能：
 * 1. 识别租户身份 (Header: X-Tenant-Id)
 * 2. 建立租户上下文隔离
 * 3. 注入 MDC 日志追踪
 */
@Component
class TenantContextFilter : GlobalFilter, Ordered {

    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        const val TENANT_HEADER = "X-Tenant-Id"
        const val TENANT_CONTEXT_KEY = "GATEWAY_TENANT_ID"
    }

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        // 1. 获取租户ID (优先从 Header 获取，后续可扩展从 JWT 解析)
        val tenantId = exchange.request.headers.getFirst(TENANT_HEADER) ?: "default"

        // 2. 存入 Exchange Attributes (供后续插件/过滤器使用)
        exchange.attributes[TENANT_CONTEXT_KEY] = tenantId

        // 3. 放入 MDC (让所有日志都带上 tenant_id)
        MdcUtils.put("tenant_id", tenantId)

        if (logger.isDebugEnabled) {
            logger.debug("🌐 Tenant Context Set: [$tenantId]")
        }

        // 4. 将租户ID 放入 Request Header 透传给下游微服务
        val newRequest = exchange.request.mutate()
            .header(TENANT_HEADER, tenantId)
            .build()

        return chain.filter(exchange.mutate().request(newRequest).build())
            .doFinally {
                MdcUtils.remove("tenant_id") // 清理 MDC
            }
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 100 // 尽早执行
}