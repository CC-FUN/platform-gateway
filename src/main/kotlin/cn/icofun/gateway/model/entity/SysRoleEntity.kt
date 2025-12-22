package cn.icofun.gateway.model.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("sys_role")
data class SysRoleEntity(
    @Id val id: Long? = null,
    val code: String,
    val name: String
)