package cn.icofun.gateway.admin.rule.data.repository
import cn.icofun.gateway.admin.rule.data.model.entity.GatewayDlpRuleEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface GatewayDlpRuleRepository : ReactiveCrudRepository<GatewayDlpRuleEntity, Long>