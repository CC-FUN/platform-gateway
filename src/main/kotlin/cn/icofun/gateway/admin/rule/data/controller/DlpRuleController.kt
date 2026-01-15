package cn.icofun.gateway.admin.rule.data.controller

import cn.icofun.gateway.admin.rule.data.model.entity.GatewayDlpRuleEntity
import cn.icofun.gateway.admin.rule.data.repository.GatewayDlpRuleRepository
import cn.icofun.gateway.admin.rule.data.service.DlpRuleService
import cn.icofun.gateway.infra.i18n.I18nMessageUtils
import cn.icofun.gateway.infra.model.StandardApiResponse
import cn.icofun.gateway.runtime.observability.audit.LogOperation
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/actuator/gateway/dlp-rules")
class DlpRuleController(
    private val dlpRuleRepository: GatewayDlpRuleRepository,
    private val dlpRuleService: DlpRuleService,
    private val i18nMessageUtils: I18nMessageUtils
) {

    @GetMapping
    fun getAll(): Mono<StandardApiResponse<List<GatewayDlpRuleEntity>>> {
        return dlpRuleRepository.findAll().collectList()
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "dlp", description = "Add DLP Rule")
    @PostMapping
    fun add(
        @RequestBody rule: GatewayDlpRuleEntity
    ): Mono<StandardApiResponse<GatewayDlpRuleEntity>> {
        return dlpRuleRepository.save(rule)
            .flatMap { saved ->
                // 联动刷新 DlpRuleService 的 Caffeine 缓存
                dlpRuleService.refreshRules()
                    .thenReturn(StandardApiResponse.success(saved))
            }
    }

    @LogOperation(module = "dlp", description = "Update DLP Rule")
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: Long,
        @RequestBody rule: GatewayDlpRuleEntity
    ): Mono<StandardApiResponse<GatewayDlpRuleEntity>> {
        return dlpRuleRepository.findById(id)
            .flatMap { existing ->
                val newEntity = rule.copy(id = existing.id)
                dlpRuleRepository.save(newEntity)
            }
            .flatMap { saved ->
                dlpRuleService.refreshRules()
                    .thenReturn(StandardApiResponse.success(saved))
            }
    }

    @LogOperation(module = "dlp", description = "Delete DLP Rule")
    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: Long,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return dlpRuleRepository.deleteById(id)
            .then(dlpRuleService.refreshRules())
            .then(Mono.defer {
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            })
    }
}