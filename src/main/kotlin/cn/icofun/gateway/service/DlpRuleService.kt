package cn.icofun.gateway.service

import cn.icofun.gateway.model.entity.GatewayDlpRuleEntity
import cn.icofun.gateway.repository.GatewayDlpRuleRepository
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit

@Service
class DlpRuleService(
    private val repository: GatewayDlpRuleRepository
) {
    // 缓存编译好的正则对象，避免每次请求都重新编译 (Key: ruleId, Value: Regex)
    private val regexCache = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build<Long, Regex>()

    // 缓存规则实体列表
    private val rulesCache = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build<String, List<GatewayDlpRuleEntity>>()

    @PostConstruct
    fun init() {
        refreshRules().subscribe()
    }

    fun getActiveRules(): Mono<List<GatewayDlpRuleEntity>> {
        val cached = rulesCache.getIfPresent("ALL")
        if (cached != null) return Mono.just(cached)
        return refreshRules()
    }

    fun getRegex(rule: GatewayDlpRuleEntity): Regex {
        return regexCache.get(rule.id!!) {
            // 编译正则，忽略大小写
            rule.regexPattern.toRegex(RegexOption.IGNORE_CASE)
        }
    }

    fun refreshRules(): Mono<List<GatewayDlpRuleEntity>> {
        return repository.findAll()
            .filter { it.enabled }
            .collectList()
            .doOnNext { list ->
                rulesCache.put("ALL", list)
                regexCache.invalidateAll() // 清除旧正则缓存
            }
    }
}