package cn.icofun.gateway.admin.rule.data.model.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("gateway_mask_field")
data class GatewayMaskFieldEntity(
    @Id
    val fieldName: String,
    val remark: String? = null
)