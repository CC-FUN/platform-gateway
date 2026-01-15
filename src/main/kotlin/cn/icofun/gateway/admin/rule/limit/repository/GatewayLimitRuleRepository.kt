package cn.icofun.gateway.admin.rule.limit.repository

import cn.icofun.gateway.admin.rule.limit.entity.GatewayLimitRuleEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository

@Repository
interface GatewayLimitRuleRepository : R2dbcRepository<GatewayLimitRuleEntity, Long>