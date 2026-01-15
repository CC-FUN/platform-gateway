package cn.icofun.gateway.admin.system.repository

import cn.icofun.gateway.admin.system.entity.SysRoleMenuEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
interface SysRoleMenuRepository : ReactiveCrudRepository<SysRoleMenuEntity, Long> {
    fun findByRoleId(roleId: Long): Flux<SysRoleMenuEntity>
    fun deleteByRoleId(roleId: Long): Mono<Void>
    fun deleteByMenuId(menuId: Long): Mono<Void>
}