package cn.icofun.gateway.admin.rule.limit.controller

import cn.icofun.gateway.infra.i18n.I18nMessageUtils
import cn.icofun.gateway.infra.model.StandardApiResponse
import cn.icofun.gateway.admin.rule.limit.entity.GatewayLimitRuleEntity
import cn.icofun.gateway.admin.rule.limit.repository.GatewayLimitRuleRepository
import cn.icofun.gateway.runtime.observability.audit.LogOperation
import cn.icofun.gateway.infra.event.CacheRefreshPublisher
import cn.icofun.gateway.infra.service.GatewayConfigService
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
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
@RequestMapping("/actuator/gateway/limit-rules")
class LimitRuleController(
    private val limitRuleRepository: GatewayLimitRuleRepository,
    private val i18nMessageUtils: I18nMessageUtils,
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val cacheRefreshPublisher: CacheRefreshPublisher
) {

    @GetMapping
    fun getAll(): Mono<StandardApiResponse<List<GatewayLimitRuleEntity>>> {
        return limitRuleRepository.findAll().collectList()
            .map { StandardApiResponse.success(it) }
    }

    /**
     * 辅助方法：同步规则到 Redis
     * Key 格式: gateway:ratelimit:config:{维度}:{值}  例如: gateway:ratelimit:config:TENANT:tenant_001
     */
    private fun syncRuleToRedis(rule: GatewayLimitRuleEntity): Mono<Boolean> {
        val redisKey = "gateway:ratelimit:config:${rule.limitKey}:${rule.limitValue}"
        val op = if (rule.status == 1) {
            // 启用状态：写入 Redis，值为阈值字符串
            redisTemplate.opsForValue().set(redisKey, rule.qpsThreshold.toString())
        } else {
            // 禁用状态：从 Redis 删除
            redisTemplate.delete(redisKey).map { true }
        }

        return op.flatMap {
            // 【核心】发送广播，通知集群清除 Caffeine 缓存
            cacheRefreshPublisher.publishRefresh(GatewayConfigService.EVENT_LIMIT_CHANGE)
                .thenReturn(true)
        }
    }

    @LogOperation(module = "limit", description = "Add Limit Rule")
    @PostMapping
    fun add(
        @RequestBody rule: GatewayLimitRuleEntity
    ): Mono<StandardApiResponse<GatewayLimitRuleEntity>> {
        return limitRuleRepository.save(rule)
            .flatMap { saved ->
                syncRuleToRedis(saved).thenReturn(StandardApiResponse.success(saved))
            }
    }

    @LogOperation(module = "limit", description = "Update Limit Rule")
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: Long,
        @RequestBody rule: GatewayLimitRuleEntity
    ): Mono<StandardApiResponse<GatewayLimitRuleEntity>> {
        return limitRuleRepository.findById(id)
            .flatMap { existing ->
                val newEntity = rule.copy(id = existing.id)
                limitRuleRepository.save(newEntity)
            }
            .flatMap { saved ->
                syncRuleToRedis(saved).thenReturn(StandardApiResponse.success(saved))
            }
    }

    @LogOperation(module = "limit", description = "Delete Limit Rule")
    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: Long,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return limitRuleRepository.findById(id)
            .flatMap { existing ->
                limitRuleRepository.deleteById(id)
                    .then(redisTemplate.delete("gateway:ratelimit:config:${existing.limitKey}:${existing.limitValue}"))
            }
            .flatMap { cacheRefreshPublisher.publishRefresh(GatewayConfigService.EVENT_LIMIT_CHANGE) }
            .then(Mono.defer {
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            })
    }
}