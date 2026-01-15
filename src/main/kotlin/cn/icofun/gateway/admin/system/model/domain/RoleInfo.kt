package cn.icofun.gateway.admin.system.model.domain

/**
 * 角色模型 (用于 Controller/Service 层传输)
 */
data class RoleInfo(
    val id: Long? = null,
    val name: String,
    val code: String
)