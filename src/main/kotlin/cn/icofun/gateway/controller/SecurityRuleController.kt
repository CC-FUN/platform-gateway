package cn.icofun.gateway.controller

import cn.icofun.gateway.annotation.LogOperation
import cn.icofun.gateway.i18n.I18nMessageUtils
import cn.icofun.gateway.model.StandardApiResponse
import cn.icofun.gateway.model.entity.GatewayConfigHistoryEntity
import cn.icofun.gateway.model.entity.GatewaySecurityRuleEntity
import cn.icofun.gateway.service.GatewaySecurityRuleService
import org.springframework.web.bind.annotation.*
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
            .map { StandardApiResponse.success(it) }
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
                Mono.just(StandardApiResponse.success(msg))
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
                Mono.just(StandardApiResponse.success(msg))

            })
    }

    @LogOperation(module = "config.security", description = "Refresh Security Rules Cache")
    @PostMapping("/refresh")
    fun refresh(exchange: ServerWebExchange): Mono<StandardApiResponse<String>> {
        return ruleService.refreshRulesCache()
            .flatMap { rules ->
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                Mono.just(StandardApiResponse.success("$msg (Loaded ${rules.size} rules)"))
            }
    }

    @GetMapping("/{id}/history")
    fun getHistory(@PathVariable id: Long): Mono<StandardApiResponse<List<GatewayConfigHistoryEntity>>> {
        return ruleService.getHistory(id)
            .collectList()
            .map { StandardApiResponse.success(it) }
    }

    // 【关键新增】回滚安全规则
    @PostMapping("/history/{historyId}/rollback")
    fun rollback(@PathVariable historyId: Long, exchange: ServerWebExchange): Mono<StandardApiResponse<String>> {
        return ruleService.rollback(historyId)
            .flatMap {
                // 返回成功消息
                Mono.just(StandardApiResponse.success("回滚成功"))
            }
    }
}