package cn.icofun.gateway.model.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("gateway_access_log")
data class GatewayAccessLogEntity(
    @Id
    val id: Long? = null,
    val traceId: String?,
    val routeId: String?,
    val requestPath: String,
    val requestMethod: String,
    val schemaName: String?,
    val responseStatus: Int,
    val clientIp: String?,
    val duration: Long,
    val requestTime: LocalDateTime,
    val errorMsg: String? = null,
    val targetUri: String? = null,
    val requestBody: String? = null,
    val responseBody: String? = null
)