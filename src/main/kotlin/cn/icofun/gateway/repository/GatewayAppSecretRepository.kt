package cn.icofun.gateway.repository

import cn.icofun.gateway.model.entity.GatewayAppSecretEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository

interface GatewayAppSecretRepository: R2dbcRepository<GatewayAppSecretEntity, String> {
}