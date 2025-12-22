package cn.icofun.gateway.repository

import cn.icofun.gateway.model.entity.GatewayRouteEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository

interface GatewayRouteRepository : R2dbcRepository<GatewayRouteEntity, String> {
}