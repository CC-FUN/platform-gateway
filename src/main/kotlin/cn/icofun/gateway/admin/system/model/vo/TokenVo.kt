package cn.icofun.gateway.admin.system.model.vo

data class TokenVo(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long, // accessToken 的过期时间，单位秒
    val grafanaToken: String
)