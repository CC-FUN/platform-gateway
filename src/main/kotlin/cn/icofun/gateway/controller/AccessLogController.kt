package cn.icofun.gateway.controller

import cn.icofun.gateway.annotation.LogOperation
import cn.icofun.gateway.model.StandardApiResponse
import cn.icofun.gateway.model.entity.GatewayAccessLogEntity
import cn.icofun.gateway.service.AccessLogService
import org.springframework.data.domain.Page
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/actuator/gateway/log")
class AccessLogController(
    private val accessLogService: AccessLogService
) {

    @LogOperation(module = "audit.log", description = "Query Access Logs")
    @GetMapping("/list")
    fun list(
        @RequestParam(required = false) routeId: String?,
        @RequestParam(required = false) status: Int?,
        @RequestParam(required = false) path: String?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "10") size: Int
    ): Mono<StandardApiResponse<Page<GatewayAccessLogEntity>>> {
        return accessLogService.queryLogs(routeId, status, path, page, size)
            .map { StandardApiResponse.success(it) }
    }
}