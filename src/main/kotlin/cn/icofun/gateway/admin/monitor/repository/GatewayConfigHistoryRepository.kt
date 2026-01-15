package cn.icofun.gateway.admin.monitor.repository

import cn.icofun.gateway.admin.monitor.model.entity.GatewayConfigHistoryEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux

interface GatewayConfigHistoryRepository : R2dbcRepository<GatewayConfigHistoryEntity, Long> {
    fun findByConfigTypeAndConfigId(configType: String, configId: String): Flux<GatewayConfigHistoryEntity>
}