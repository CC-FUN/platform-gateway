package cn.icofun.gateway.admin.route.model.dto

import cn.icofun.gateway.infra.test.MockHttpRequest

data class RouteSimulationRequest(
    // 待测试的路由配置 (DTO)
    val routeConfig: GatewayRouteDTO,
    // 模拟的 HTTP 请求
    val mockRequest: MockHttpRequest
)