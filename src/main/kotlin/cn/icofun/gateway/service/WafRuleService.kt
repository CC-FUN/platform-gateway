package cn.icofun.gateway.service

import cn.icofun.gateway.model.entity.GatewayWafRuleEntity
import cn.icofun.gateway.repository.GatewayWafRuleRepository
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit

@Service
class WafRuleService(
    private val repository: GatewayWafRuleRepository
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    // L1 本地缓存：缓存所有启用的 WAF 规则，避免每次请求查库
    private val ruleCache = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build<String, List<GatewayWafRuleEntity>>()

    @PostConstruct
    fun init() {
        refreshRules().subscribe()
    }

    /**
     * 【核心方法】获取所有激活的 WAF 规则 (带缓存)
     * 供 WafPlugin 调用
     */
    fun getActiveRules(): Flux<GatewayWafRuleEntity> {
        val cached = ruleCache.getIfPresent("ACTIVE_RULES")
        if (cached != null) {
            logger.debug("[WAF] Using cached rules, count: ${cached.size}")
            return Flux.fromIterable(cached)
        }
        logger.debug("[WAF] Cache miss, loading rules from database...")
        // 如果缓存未命中，查库并回填
        return refreshRules().flatMapMany { Flux.fromIterable(it) }
    }

    /**
     * 刷新缓存 (当规则变更时调用)
     */
    fun refreshRules(): Mono<List<GatewayWafRuleEntity>> {
        return repository.findByEnabledTrue()
            // 按优先级降序排列
            .sort(Comparator.comparingInt(GatewayWafRuleEntity::priority).reversed())
            .collectList()
            .doOnNext { rules ->
                ruleCache.put("ACTIVE_RULES", rules)
                logger.info("🛡️ WAF 规则缓存已刷新 (Count: ${rules.size})")
            }
    }
}