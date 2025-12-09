package cn.icofun.gateway.controller

import cn.icofun.gateway.model.dto.StandardApiResponse
import cn.icofun.gateway.service.MonitorService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/actuator/gateway/monitor")
class MonitorController(
    private val monitorService: MonitorService
) {
    @GetMapping("stats")
    fun getDashboardStats(): Mono<StandardApiResponse<Map<String, Any>>> {
        return monitorService.getDashboardStats()
            .map { StandardApiResponse.success(it) }
    }

    @GetMapping("/trend")
    fun getTrafficTrend(): Mono<StandardApiResponse<List<Int>>> {
        return monitorService.getTrendData()
            .map { StandardApiResponse.success(it) }
    }

    @GetMapping("/services")
    fun getServiceRankings(): Mono<StandardApiResponse<List<Map<String, Any>>>> {
        return monitorService.getServiceRankings()
            .map { StandardApiResponse.success(it) }
    }

}