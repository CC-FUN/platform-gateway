package cn.icofun.gateway.model.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("sys_menu")
data class SysMenuEntity(
    @Id
    val id: Long? = null,
    val parentId: Long = 0,
    val title: String,
    val path: String,
    val component: String?,
    val permCode: String?,
    val icon: String?,
    val type: Int,
    val sortOrder: Int
)