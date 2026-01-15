package cn.icofun.gateway.admin.rule.security.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("gateway_whitelist")
data class GatewayWhitelistEntity(
    @Id
    val id: Long? = null,
    val path: String,
    val type: String,
    val remark: String? = null
)