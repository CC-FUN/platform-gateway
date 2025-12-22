package cn.icofun.gateway.repository
import cn.icofun.gateway.model.entity.GatewayDlpRuleEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface GatewayDlpRuleRepository : ReactiveCrudRepository<GatewayDlpRuleEntity, Long>