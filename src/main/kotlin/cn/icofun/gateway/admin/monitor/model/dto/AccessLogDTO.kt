package cn.icofun.gateway.admin.monitor.model.dto

import java.time.LocalDateTime

data class AccessLogDTO(
    val requestId: String,
    val method: String,
    val path: String,
    val ip: String,
    val queryParams: String?,
    val requestBody: String?,
    val responseBody: String?,
    val status: Int,
    val duration: Long,
    val createTime: String = LocalDateTime.now().toString(),
    val serviceId: String?
)