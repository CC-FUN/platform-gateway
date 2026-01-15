package cn.icofun.gateway.admin.route.model.dto

data class RouteSimulationResult(
    val matched: Boolean,
    val matchDetails: String, // 匹配失败的原因或匹配成功的 Predicate 信息
    val activePlugins: List<String>, // 会触发的插件列表
    val limitRules: Map<String, Any>? = null, // 会命中的限流规则
    val timeCost: Long = 0 // 添加此字段解决 "No parameter with name 'timeCost' found"
)