package cn.icofun.gateway.admin.rule.security.repository

import cn.icofun.gateway.admin.rule.security.entity.GatewayWhitelistEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface GatewayWhitelistRepository : R2dbcRepository<GatewayWhitelistEntity, Long> {
    fun findByType(type: String): Flux<GatewayWhitelistEntity>
    fun deleteByTypeAndPath(type: String, path: String): Mono<Void>
}