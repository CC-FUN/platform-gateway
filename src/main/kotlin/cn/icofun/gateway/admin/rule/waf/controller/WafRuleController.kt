package cn.icofun.gateway.admin.rule.waf.controller

import cn.icofun.gateway.infra.i18n.I18nMessageUtils
import cn.icofun.gateway.infra.model.StandardApiResponse
import cn.icofun.gateway.admin.rule.waf.model.entity.GatewayWafRuleEntity
import cn.icofun.gateway.admin.rule.waf.repository.GatewayWafRuleRepository
import cn.icofun.gateway.runtime.observability.audit.LogOperation
import cn.icofun.gateway.admin.rule.waf.service.WafRuleService
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
@RequestMapping("/actuator/gateway/waf-rules")
class WafRuleController(
    private val wafRuleRepository: GatewayWafRuleRepository,
    private val wafRuleService: WafRuleService,
    private val i18nMessageUtils: I18nMessageUtils
) {

    @GetMapping
    fun getAll(): Mono<StandardApiResponse<List<GatewayWafRuleEntity>>> {
        return wafRuleRepository.findAll().collectList()
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "waf", description = "Add WAF Rule")
    @PostMapping
    fun add(@RequestBody rule: GatewayWafRuleEntity): Mono<StandardApiResponse<GatewayWafRuleEntity>> {
        return wafRuleRepository.save(rule)
            .flatMap { saved ->
                wafRuleService.refreshRules().thenReturn(StandardApiResponse.success(saved))
            }
    }

    @LogOperation(module = "waf", description = "Update WAF Rule")
    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody rule: GatewayWafRuleEntity): Mono<StandardApiResponse<GatewayWafRuleEntity>> {
        return wafRuleRepository.findById(id)
            .flatMap { existing ->
                wafRuleRepository.save(rule.copy(id = existing.id))
            }
            .flatMap { saved ->
                wafRuleService.refreshRules().thenReturn(StandardApiResponse.success(saved))
            }
    }

    @LogOperation(module = "waf", description = "Delete WAF Rule")
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long, exchange: ServerWebExchange): Mono<StandardApiResponse<String>> {
        return wafRuleRepository.deleteById(id)
            .then(wafRuleService.refreshRules())
            .then(Mono.fromCallable {
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                StandardApiResponse.success(msg)
            })
    }
}