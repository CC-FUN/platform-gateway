package cn.icofun.gateway.admin.monitor.model.dto

import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDateTime

data class AttackLogSearchCondition(
    val attackType: String? = null,
    val clientIp: String? = null,
    @field:DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    val startTime: LocalDateTime? = null,
    @field:DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    val endTime: LocalDateTime? = null,
    val page: Int = 1,
    val size: Int = 10
)