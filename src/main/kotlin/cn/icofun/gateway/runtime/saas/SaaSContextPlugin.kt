package cn.icofun.gateway.runtime.saas

import cn.icofun.gateway.core.context.GatewayContext
import cn.icofun.gateway.core.context.SaaSContext
import cn.icofun.gateway.core.engine.PluginChain
import cn.icofun.gateway.core.spi.GatewayPlugin
import cn.icofun.gateway.infra.utils.MdcUtils
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class SaaSContextPlugin : GatewayPlugin {

    // 移除 Logger 定义，高性能场景下非异常路径尽量少打日志，或者使用静态 Logger
    // private val logger = LoggerFactory.getLogger(this::class.java)

    override fun getName() = "SaaSContextBuild"
    // 优先级极高，确保最先执行
    override fun getOrder() = -1000

    companion object {
        const val TENANT_HEADER = "X-Tenant-Id"
        const val BIZ_TYPE_HEADER = "X-Biz-Type"
        // 预定义默认值，避免重复创建字符串对象
        const val DEFAULT_TENANT = "default"
        const val DEFAULT_BIZ_TYPE = "sync"
    }

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val exchange = context.exchange
        val headers = exchange.request.headers

        // 1. 获取租户ID (快路径)
        // 避免 unnecessary null check，使用 elvis operator
        val tenantId = headers.getFirst(TENANT_HEADER) ?: DEFAULT_TENANT

        // 2. 获取业务类型 (Map 查找优化)
        val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
        val bizType = route?.metadata?.get("biz_type") as? String

        // 3. 构建 SaaS 上下文 (轻量级对象)
        val saasContext = SaaSContext(
            tenantId = tenantId,
            bizType = bizType,
            traceId = exchange.request.id
        )

        // 4. 存入 Attribute (这是最快的内部传递方式)
        exchange.attributes[SaaSContext.KEY] = saasContext

        // 5. MDC 注入 (注意：高并发下 ThreadLocal 操作有开销，如非必须可移除)
        // 建议仅在 Debug 模式或确实需要日志追踪时开启
        MdcUtils.put("tenant_id", tenantId)
        MdcUtils.put("biz_type", bizType ?: DEFAULT_BIZ_TYPE)

        // 6. 极致性能优化：按需突变 (Cow - Copy On Write 思想)
        // 只有当 Header 缺失或需要补充时，才执行昂贵的 mutate 操作
        val currentTenantHeader = headers.getFirst(TENANT_HEADER)
        val currentBizHeader = headers.getFirst(BIZ_TYPE_HEADER)

        // 如果 Header 已经完全一致，直接复用原 Exchange，避免创建 Request/Exchange 包装对象
        if (currentTenantHeader == tenantId && currentBizHeader == bizType) {
            return chain.execute(context)
        }

        // 7. 必须突变时，执行构建
        val requestBuilder = exchange.request.mutate()
        if (currentTenantHeader != tenantId) {
            requestBuilder.header(TENANT_HEADER, tenantId)
        }
        if (currentBizHeader != bizType) {
            requestBuilder.header(BIZ_TYPE_HEADER, bizType ?: "")
        }

        // 修复 BUG: 必须将 mutatedExchange 传递给 chain，否则 Header 修改无效
        val mutatedExchange = exchange.mutate().request(requestBuilder.build()).build()

        // 假设 GatewayContext 是 data class，使用 copy 方法传递新 exchange
        // 这样下游插件拿到的才是带新 Header 的 exchange
        return chain.execute(context.copy(exchange = mutatedExchange))
    }
}