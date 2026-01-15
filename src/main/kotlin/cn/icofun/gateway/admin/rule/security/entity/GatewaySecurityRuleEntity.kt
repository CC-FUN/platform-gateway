package cn.icofun.gateway.admin.rule.security.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

/**
 * 安全规则配置表
 */
@Table("gateway_security_rule")
data class GatewaySecurityRuleEntity(
    @Id val id: Long? = null,
    val path: String,       // 例如 "/sys/**"
    val method: String?,    // 例如 "GET"，空代表所有
    val type: String,       // "PERMIT_ALL" (放行), "AUTHENTICATED" (需登录)
    val priority: Int = 0   // 优先级，数字越大越优先匹配
)