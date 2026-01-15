package cn.icofun.gateway.admin.rule.data.model.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("gateway_dlp_rule")
data class GatewayDlpRuleEntity(
    @Id val id: Long? = null,
    val ruleName: String,
    val regexPattern: String,
    val maskChar: String = "*",
    val enabled: Boolean = true,
    val description: String? = null
)