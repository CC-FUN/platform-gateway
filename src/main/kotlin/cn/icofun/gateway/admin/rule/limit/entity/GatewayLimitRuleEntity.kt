package cn.icofun.gateway.admin.rule.limit.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("gateway_limit_rule")
data class GatewayLimitRuleEntity(
    @Id val id: Long? = null,
    val limitKey: String,   // 对应表中的 limit_key，支持 "TENANT"
    val limitValue: String, // 对应具体的租户ID或IP
    val qpsThreshold: Int,
    val status: Int = 1,
    val description: String? = null,
    val createTime: LocalDateTime = LocalDateTime.now()
)