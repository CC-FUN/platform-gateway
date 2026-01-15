package cn.icofun.gateway.admin.monitor.controller.log

import cn.icofun.gateway.infra.model.StandardApiResponse
import cn.icofun.gateway.admin.monitor.model.dto.AttackLogSearchCondition
import cn.icofun.gateway.admin.monitor.model.entity.GatewayAttackLogEntity
import cn.icofun.gateway.admin.monitor.service.log.AttackLogService
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/actuator/gateway/attack-logs")
class AttackLogController(
    private val attackLogService: AttackLogService
) {

    @PostMapping("/search")
    fun search(@RequestBody condition: AttackLogSearchCondition): Mono<StandardApiResponse<Map<String, Any>>> {
        return attackLogService.searchLogs(
            condition.attackType,
            condition.clientIp,
            condition.startTime,
            condition.endTime,
            condition.page,
            condition.size
        ).map { StandardApiResponse.success(it) }
    }

    /**
     * 获取单条攻击证据详情 (展示 Raw Request)
     */
    @GetMapping("/{id}")
    fun getDetail(@PathVariable id: Long): Mono<StandardApiResponse<GatewayAttackLogEntity>> {
        return attackLogService.getById(id)
            .map { StandardApiResponse.success(it) }
    }
}