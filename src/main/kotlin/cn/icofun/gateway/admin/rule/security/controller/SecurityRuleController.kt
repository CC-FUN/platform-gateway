package cn.icofun.gateway.admin.rule.security.controller

import cn.icofun.gateway.admin.rule.limit.service.GatewaySecurityRuleService
import cn.icofun.gateway.infra.i18n.I18nMessageUtils
import cn.icofun.gateway.infra.model.StandardApiResponse
import cn.icofun.gateway.admin.monitor.model.entity.GatewayConfigHistoryEntity
import cn.icofun.gateway.admin.rule.security.entity.GatewaySecurityRuleEntity
import cn.icofun.gateway.runtime.observability.audit.LogOperation
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/actuator/gateway/security/rules")
class SecurityRuleController(
    private val ruleService: GatewaySecurityRuleService,
    private val i18nMessageUtils: I18nMessageUtils,
) {

    @GetMapping
    fun list(): Mono<StandardApiResponse<List<GatewaySecurityRuleEntity>>> {
        return ruleService.findAll()
            .map { StandardApiResponse. success(it) }
    }

    @LogOperation(module = "config.security", description = "Save Security Rule")
    @PostMapping
    fun save(
        @RequestBody rule: GatewaySecurityRuleEntity,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return ruleService.save(rule)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                Mono.just(StandardApiResponse. success(msg))
            }
    }

    @LogOperation(module = "config.security", description = "Delete Security Rule")
    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: Long,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return ruleService.delete(id)
            .then(Mono.defer {
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                Mono.just(StandardApiResponse. success(msg))

            })
    }

    @LogOperation(module = "config.security", description = "Refresh Security Rules Cache")
    @PostMapping("/refresh")
    fun refresh(exchange: ServerWebExchange): Mono<StandardApiResponse<String>> {
        return ruleService.refreshRulesCache()
            .flatMap { rules ->
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                Mono.just(StandardApiResponse. success("$msg (Loaded ${rules.size} rules)"))
            }
    }

    @GetMapping("/{id}/history")
    fun getHistory(@PathVariable id: Long): Mono<StandardApiResponse<List<GatewayConfigHistoryEntity>>> {
        return ruleService.getHistory(id)
            .collectList()
            .map { StandardApiResponse. success(it) }
    }

    // 【关键新增】回滚安全规则
    @PostMapping("/history/{historyId}/rollback")
    fun rollback(@PathVariable historyId: Long): Mono<StandardApiResponse<String>> {
        return ruleService.rollback(historyId)
            .flatMap {
                // 返回成功消息
                Mono.just(StandardApiResponse. success("回滚成功"))
            }
    }
}