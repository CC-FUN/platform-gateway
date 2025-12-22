package cn.icofun.gateway.repository

import cn.icofun.gateway.model.entity.SysUserEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Mono

interface SysUserRepository: R2dbcRepository<SysUserEntity, Long> {
    fun findByUsername(usernae: String): Mono<SysUserEntity>
}