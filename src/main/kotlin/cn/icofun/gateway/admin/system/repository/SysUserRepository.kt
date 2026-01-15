package cn.icofun.gateway.admin.system.repository

import cn.icofun.gateway.admin.system.entity.SysUserEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Mono

interface SysUserRepository: R2dbcRepository<SysUserEntity, Long> {
    fun findByUsername(usernae: String): Mono<SysUserEntity>
}