package cn.icofun.gateway.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.LocalDateTime

@JsonIgnoreProperties(ignoreUnknown = true)
data class SysUser(
    val id: Long? = null,
    val username: String? = null,
    val password: String? = null,
    val role: String? = null,
    val roles: List<SysRole>? = null,
    val roleIds: List<Long>? = null,
    val allowedMenus: List<String>? = null,
    val enabled: Boolean = true,
    val createTime: LocalDateTime? = null
) {
    @JsonIgnore
    fun isSuperAdmin(): Boolean {
        if ("admin" == username) return true
        if ("SUPER_ADMIN".equals(this.role, ignoreCase = true)) return true
        return roles?.any { "SUPER_ADMIN".equals(it.code, ignoreCase = true) } == true
    }
}