package cn.icofun.gateway.admin.rule.waf.repository

import cn.icofun.gateway.admin.rule.waf.model.entity.GatewayWafRuleEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux

@Repository
interface GatewayWafRuleRepository : ReactiveCrudRepository<GatewayWafRuleEntity, Long> {
    // 查找所有启用的规则
    fun findByEnabledTrue(): Flux<GatewayWafRuleEntity>
}