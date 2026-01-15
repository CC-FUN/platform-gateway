package cn.icofun.gateway.admin.route.model.vo

data class RouterVo(
    val name: String,
    val path: String,
    val component: String,
    val meta: MetaVo,
    val children: List<RouterVo>? = null
)