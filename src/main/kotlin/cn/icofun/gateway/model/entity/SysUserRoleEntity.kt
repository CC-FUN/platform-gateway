package cn.icofun.gateway.model.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("sys_user_role")
data class SysUserRoleEntity(
    @Id
    val id: Long? = null,
    val userId: Long,
    val roleId: Long
)