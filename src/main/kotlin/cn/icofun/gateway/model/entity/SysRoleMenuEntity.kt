package cn.icofun.gateway.model.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("sys_role_menu")
data class SysRoleMenuEntity(
    @Id
    val id: Long? = null,
    val roleId: Long,
    val menuId: Long
)