package cn.icofun.gateway.repository

import cn.icofun.gateway.model.entity.SysUserRoleEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
interface SysUserRoleRepository : ReactiveCrudRepository<SysUserRoleEntity, Long> {
    fun findByUserId(userId: Long): Flux<SysUserRoleEntity>
    fun deleteByUserId(userId: Long): Mono<Void>
    fun findByRoleId(roleId: Long): Flux<SysUserRoleEntity>
}