package cn.icofun.gateway.repository

import cn.icofun.gateway.model.entity.GatewayMaskFieldEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository

interface GatewayMaskFieldRepository : R2dbcRepository<GatewayMaskFieldEntity, String> {
}