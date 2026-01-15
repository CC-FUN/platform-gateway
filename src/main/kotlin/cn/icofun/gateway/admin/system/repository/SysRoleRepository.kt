package cn.icofun.gateway.admin.system.repository

import cn.icofun.gateway.admin.system.entity.SysRoleEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Mono

interface SysRoleRepository : R2dbcRepository<SysRoleEntity, Long> {
    fun findByCode(code: String): Mono<SysRoleEntity>
}