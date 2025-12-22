package cn.icofun.gateway.repository

import cn.icofun.gateway.model.entity.GatewayProjectionFieldEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface GatewayProjectionFieldRepository : ReactiveCrudRepository<GatewayProjectionFieldEntity, String>