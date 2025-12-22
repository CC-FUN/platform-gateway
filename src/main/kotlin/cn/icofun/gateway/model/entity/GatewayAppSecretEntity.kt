package cn.icofun.gateway.model.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("gateway_app_secret")
data class GatewayAppSecretEntity(
    @Id
    val appId: String,
    val appSecret: String,
    val remark: String? = null
)