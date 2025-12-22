package cn.icofun.gateway.repository

import cn.icofun.gateway.model.entity.GatewaySecurityRuleEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository

@Repository
interface GatewaySecurityRuleRepository : R2dbcRepository<GatewaySecurityRuleEntity, Long>