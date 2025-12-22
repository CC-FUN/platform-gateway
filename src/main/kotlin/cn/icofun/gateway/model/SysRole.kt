package cn.icofun.gateway.model

/**
 * 角色模型 (用于 Controller/Service 层传输)
 */
data class SysRole(
    val id: Long? = null,
    val name: String,
    val code: String
)