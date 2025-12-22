package cn.icofun.gateway.repository

import cn.icofun.gateway.model.entity.GatewayLimitRuleEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository

@Repository
interface GatewayLimitRuleRepository : R2dbcRepository<GatewayLimitRuleEntity, Long>