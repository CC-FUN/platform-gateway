package cn.icofun.gateway.admin.route.repository

import cn.icofun.gateway.admin.route.model.entity.GatewayRouteEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository

interface GatewayRouteRepository : R2dbcRepository<GatewayRouteEntity, String> {
}