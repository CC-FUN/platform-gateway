package cn.icofun.gateway.admin.rule.security.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("ip_blacklist")
data class IpBlacklistEntity(
    @Id
    val ip: String,
    val createTime: LocalDateTime? = LocalDateTime.now(),
    val remark: String? = null,
)