package cn.icofun.gateway.model.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("gateway_config_history")
data class GatewayConfigHistoryEntity(
    @Id val id: Long? = null,
    val configType: String,         // 配置类型: ROUTE, I18N_MESSAGE, etc.
    val configId: String,           // 业务ID (如 route_id, app_id)
    val snapshot: String,           // JSON 快照 或 操作描述
    val operator: String,           // 操作人
    val description: String?,       // 变更描述
    val createTime: LocalDateTime = LocalDateTime.now()
)