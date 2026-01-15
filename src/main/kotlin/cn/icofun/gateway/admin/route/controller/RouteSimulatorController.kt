package cn.icofun.gateway.admin.route.controller

import cn.icofun.gateway.infra.test.MockHttpRequest
import cn.icofun.gateway.admin.route.model.dto.RouteSimulationResult
import cn.icofun.gateway.infra.model.StandardApiResponse
import cn.icofun.gateway.admin.route.model.dto.GatewayRouteDTO
import cn.icofun.gateway.runtime.observability.audit.LogOperation
import cn.icofun.gateway.admin.route.service.RouteSimulatorService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/actuator/gateway/dry-run")
class RouteSimulatorController(
    private val simulatorService: RouteSimulatorService
) {

    // 前端请求体的包装类
    data class SimulationRequestWrapper(
        val route: GatewayRouteDTO,          // 待测试的路由配置
        val method: String = "GET",          // 模拟请求方法
        val path: String,                    // 模拟请求路径
        val headers: Map<String, String>? = null,
        val queryParams: Map<String, String>? = null,
        val body: String? = null,
        val remoteIp: String? = "127.0.0.1"
    )

    @LogOperation(module = "ops", description = "Dry-Run Route Simulation")
    @PostMapping("/simulate")
    fun simulate(@RequestBody request: SimulationRequestWrapper): Mono<StandardApiResponse<RouteSimulationResult>> {
        return Mono.fromCallable {
            val start = System.currentTimeMillis()

            // 1. 构建 Service 需要的 MockHttpRequest 对象
            // 解决 "Argument type mismatch: actual type is 'String', but 'MockHttpRequest' was expected"
            val mockReq = MockHttpRequest(
                uri = request.path,
                method = request.method,
                headers = request.headers ?: emptyMap(),
                queryParams = request.queryParams ?: emptyMap(),
                remoteIp = request.remoteIp ?: "127.0.0.1"
            )

            // 2. 调用 Service (现在参数匹配了)
            // 解决 "Too many arguments..."
            val result = simulatorService.simulate(request.route, mockReq)

            val cost = System.currentTimeMillis() - start

            // 3. 返回结果，并填充耗时
            // 解决 "No parameter with name 'timeCost' found" (因为我们在 Model 里加了)
            StandardApiResponse.success(result.copy(timeCost = cost))
        }
    }
}