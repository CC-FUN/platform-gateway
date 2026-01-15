package cn.icofun.gateway.core.context

import java.io.Serializable

/**
 * SaaS 业务上下文
 * 存放本次请求的租户信息、业务意图等核心元数据
 */
data class SaaSContext(
    val tenantId: String,           // 租户ID (核心)
    val tenantPlan: String = "FREE",// 租户套餐: FREE, PRO, VIP (用于限流)
    val bizType: String? = null,    // 业务类型: order.create (用于异步分发)
    val traceId: String,            // 链路追踪ID
    val userId: String? = null      // 用户ID (可选)
) : Serializable {
    companion object {
        // 存放在 Exchange Attributes 中的 Key
        const val KEY = "CTX_SAAS"
    }
}