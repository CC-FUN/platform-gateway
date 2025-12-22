package cn.icofun.gateway.model

data class RouteSimulationRequest(
    // 待测试的路由配置 (DTO)
    val routeConfig: cn.icofun.gateway.model.dto.GatewayRouteDTO,
    // 模拟的 HTTP 请求
    val mockRequest: MockHttpRequest
)