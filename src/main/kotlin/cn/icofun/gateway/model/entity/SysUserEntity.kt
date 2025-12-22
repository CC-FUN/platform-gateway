package cn.icofun.gateway.model.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("sys_user")
data class SysUserEntity(
    @Id
    val id: Long? = null,
    val username: String,
    val password: String,
    val enabled: Boolean = true,
    val createTime: LocalDateTime? = null
)