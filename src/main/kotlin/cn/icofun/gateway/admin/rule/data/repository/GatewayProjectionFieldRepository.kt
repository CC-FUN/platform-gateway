package cn.icofun.gateway.admin.rule.data.repository

import cn.icofun.gateway.admin.rule.data.model.entity.GatewayProjectionFieldEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface GatewayProjectionFieldRepository : ReactiveCrudRepository<GatewayProjectionFieldEntity, String>