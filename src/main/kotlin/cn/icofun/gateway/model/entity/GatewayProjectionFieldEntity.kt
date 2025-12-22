package cn.icofun.gateway.model.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("gateway_projection_field")
data class GatewayProjectionFieldEntity(
    @Id
    val fieldName: String,
    val remark: String? = null,
    val createTime: LocalDateTime? = null
)