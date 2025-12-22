package cn.icofun.gateway.controller

import cn.icofun.gateway.model.StandardApiResponse
import cn.icofun.gateway.model.dto.AttackLogSearchCondition
import cn.icofun.gateway.model.entity.GatewayAttackLogEntity
import cn.icofun.gateway.service.AttackLogService
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