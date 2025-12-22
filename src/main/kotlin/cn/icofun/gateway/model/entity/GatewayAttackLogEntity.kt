package cn.icofun.gateway.model.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("gateway_attack_log")
data class GatewayAttackLogEntity(
    @Id val id: Long? = null,
    val traceId: String,
    val ruleId: Long?,
    val ruleName: String?,
    val attackType: String,
    val clientIp: String,
    val requestUri: String,
    val requestMethod: String,
    val rawRequest: String, // 核心字段：报文镜像
    val attackTime: LocalDateTime = LocalDateTime.now()
)