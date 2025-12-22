package cn.icofun.gateway.model.dto

/**
 * 前端交互专用的路由传输对象
 */
data class GatewayRouteDTO(
    val id: String,
    val uri: String,
    val order: Int = 0,
    // 默认启用
    val enabled: Boolean = true,
    // 备注信息
    val description: String? = null,
    // 谓词集合 (断言)
    val predicates: List<CustomPredicateDTO> = emptyList(),
    // 过滤器集合
    val filters: List<CustomFilterDTO> = emptyList(),
    // 元数据
    val metadata: Map<String, Any> = emptyMap()
) {
}

// 【新增】自定义断言传输类，支持 args 的 value 为任意类型 (如 Array)
data class CustomPredicateDTO(
    val name: String,
    val args: Map<String, Any> = emptyMap()
)

// 【新增】自定义过滤器传输类
data class CustomFilterDTO(
    val name: String,
    val args: Map<String, Any> = emptyMap()
)