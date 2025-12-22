package cn.icofun.gateway.repository

import cn.icofun.gateway.model.entity.SysRoleEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Mono

interface SysRoleRepository : R2dbcRepository<SysRoleEntity, Long> {
    fun findByCode(code: String): Mono<SysRoleEntity>
}