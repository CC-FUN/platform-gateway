package cn.icofun.gateway.admin.rule.data.repository

import cn.icofun.gateway.admin.rule.data.model.entity.GatewayMaskFieldEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository

interface GatewayMaskFieldRepository : R2dbcRepository<GatewayMaskFieldEntity, String> {
}