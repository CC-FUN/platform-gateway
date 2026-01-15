package cn.icofun.gateway.admin.rule.security.repository

import cn.icofun.gateway.admin.rule.security.entity.GatewayAppSecretEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository

interface GatewayAppSecretRepository: R2dbcRepository<GatewayAppSecretEntity, String> {
}