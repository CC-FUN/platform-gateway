package cn.icofun.gateway.service

import cn.icofun.gateway.exception.BusinessException
import cn.icofun.gateway.model.SysRole
import cn.icofun.gateway.model.entity.GatewayConfigHistoryEntity
import cn.icofun.gateway.model.entity.SysRoleEntity
import cn.icofun.gateway.model.entity.SysRoleMenuEntity
import cn.icofun.gateway.repository.*
import cn.icofun.gateway.utils.SecurityUtils
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Service
class RoleService(
    private val roleRepository: SysRoleRepository,
    private val roleMenuRepository: SysRoleMenuRepository,
    private val userRoleRepository: SysUserRoleRepository,
    private val userRepository: SysUserRepository,
    private val menuRepository: SysMenuRepository,
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val historyRepository: GatewayConfigHistoryRepository,
    private val objectMapper: ObjectMapper
) {

    private val CONFIG_TYPE = "SYSTEM_ROLE"
    private val CONFIG_TYPE_PERM = "ROLE_PERMISSIONS"

    fun findAll(): Flux<SysRole> {
        return roleRepository.findAll()
            .map {
                SysRole(
                    id = it.id,
                    name = it.name,
                    code = it.code,
                )
            }
    }

    @Transactional
    fun save(role: SysRole): Mono<SysRoleEntity> {
        if (role.name.isBlank()) {
            return Mono.error(BusinessException(400, "role.name.missing"))
        }
        if (role.code.isBlank()) {
            return Mono.error(BusinessException(400, "role.code.missing"))
        }

        val entity = SysRoleEntity(
            id = role.id,
            name = role.name,
            code = role.code,
        )

        return if (entity.id != null) {
            roleRepository.findById(entity.id)
                .flatMap { existing ->
                    if (existing.name == entity.name && existing.code == entity.code) {
                        return@flatMap Mono.just(existing)
                    }
                    recordHistory(existing.id.toString(), "Update Role (Before)", existing)
                        .then(roleRepository.save(entity.copy(id = existing.id)))
                        .flatMap { saved ->
                            // 记录更新后快照
                            recordHistory(saved.id.toString(), "Save Role (After)", saved)
                                .thenReturn(saved)
                        }
                }
                .switchIfEmpty(
                    roleRepository.save(entity).flatMap { saved ->
                        recordHistory(saved.id.toString(), "Create Role (After)", saved).thenReturn(saved)
                    }
                )
        } else {
            roleRepository.save(entity).flatMap { saved ->
                recordHistory(saved.id.toString(), "Create Role (After)", saved).thenReturn(saved)
            }
        }
    }

    @Transactional
    fun delete(id: Long): Mono<Void> {
        if (id <= 0) {
            return Mono.error(BusinessException(400, "sys.arg.id.invalid"))
        }
        return roleRepository.findById(id)
            .flatMap { existing ->
                recordHistory(existing.id.toString(), "Delete Role", existing)
                    .then(roleMenuRepository.deleteByRoleId(id))
                    .then(roleRepository.deleteById(id))
            }
            .then(clearUserRoutersByRoleId(id))
    }

    fun findMenuIdsByRoleId(roleId: Long): Mono<List<Long>> {
        if (roleId <= 0) {
            return Mono.error(BusinessException(400, "sys.arg.id.invalid"))
        }
        return roleMenuRepository.findByRoleId(roleId)
            .map { it.menuId }
            .collectList()
    }

    @Transactional
    fun saveRoleMenus(roleId: Long, menuIds: List<Long>): Mono<Void> {
        if (roleId <= 0) {
            return Mono.error(BusinessException(400, "sys.arg.id.invalid"))
        }

        return findMenuIdsByRoleId(roleId)
            .flatMap { currentMenuIds ->
                if (currentMenuIds.sorted() == menuIds.sorted()) {
                    return@flatMap Mono.empty<Void>()
                }

                val createSnapshot = { ids: List<Long> ->
                    if (ids.isEmpty()) Mono.just("[]")
                    else menuRepository.findAllById(ids)
                        .map { "${it.title}(${it.id})" } // 格式：菜单名(ID)
                        .collectList()
                        .map { objectMapper.writeValueAsString(it) }
                }

                createSnapshot(currentMenuIds).flatMap { beforeSnapshot ->
                    createSnapshot(menuIds).flatMap { afterSnapshot ->
                        // 记录 Before
                        recordPermissionHistory(roleId, "Update Permissions (Before)", beforeSnapshot)
                            .then(roleMenuRepository.deleteByRoleId(roleId))
                            .then(
                                if (menuIds.isNotEmpty()) {
                                    val entities = menuIds.map { SysRoleMenuEntity(roleId = roleId, menuId = it) }
                                    roleMenuRepository.saveAll(entities).then()
                                } else Mono.empty()
                            )
                            // 记录 After
                            .then(recordPermissionHistory(roleId, "Update Permissions (After)", afterSnapshot))
                    }
                }
                    .then(clearUserRoutersByRoleId(roleId))
            }
    }

    private fun clearUserRoutersByRoleId(roleId: Long): Mono<Void> {
        return userRoleRepository.findByRoleId(roleId) // 假设你有这个方法，如果没有需在 Repository 补充
            .flatMap { userRole -> userRepository.findById(userRole.userId) }
            .map { user -> "gateway:user:routers:${user.username}" }
            .collectList()
            .flatMap { keys ->
                if (keys.isNotEmpty()) {
                    redisTemplate.delete(Flux.fromIterable(keys)).then()
                } else {
                    Mono.empty()
                }
            }
    }

    // 【新增】批量查找角色 (用于 SystemController 检查权限越权)
    fun findAllByIds(ids: List<Long>): Flux<SysRole> {
        return roleRepository.findAllById(ids)
            .map { SysRole(it.id, it.name, it.code) }
    }

    // 【新增】单个查找
    fun findById(id: Long): Mono<SysRole> {
        return roleRepository.findById(id)
            .map { SysRole(it.id, it.name, it.code) }
    }

    // 【新增】角色回滚功能
    @Transactional
    fun rollback(historyId: Long): Mono<SysRole> {
        return historyRepository.findById(historyId)
            .switchIfEmpty(Mono.error(BusinessException(404, "sys.config.history.not_found")))
            .flatMap { history ->
                if (history.configType != CONFIG_TYPE) {
                    return@flatMap Mono.error(BusinessException(400, "sys.config.type.mismatch"))
                }

                // 还原实体
                val entity = objectMapper.readValue(history.snapshot, SysRoleEntity::class.java)

                roleRepository.save(entity)
                    .flatMap { saved ->
                        // 记录回滚操作
                        recordHistory(saved.id.toString(), "Rollback to ver ${history.id}", saved).subscribe()
                        // 强制刷新所有受影响用户的菜单缓存
                        clearUserRoutersByRoleId(saved.id!!).then()
                    }.thenReturn(SysRole(entity.id, entity.name, entity.code))
            }
    }

    // 【新增】角色权限回滚功能
    @Transactional
    fun rollbackRoleMenus(historyId: Long): Mono<Void> {
        return historyRepository.findById(historyId)
            .switchIfEmpty(Mono.error(BusinessException(404, "sys.config.history.not_found")))
            .flatMap { history ->
                if (history.configType != CONFIG_TYPE_PERM) {
                    return@flatMap Mono.error(BusinessException(400, "sys.config.type.mismatch"))
                }

                val roleId = history.configId.toLong()

                // 1. 还原历史的 menuIds
                val typeRef = object : TypeReference<List<String>>() {}
                val snapshotList = objectMapper.readValue(history.snapshot, typeRef)

                val restoredMenuIds = snapshotList.mapNotNull {
                    Regex("""\((\d+)\)""").find(it)?.groupValues?.get(1)?.toLong()
                }

                val updateAction = roleMenuRepository.deleteByRoleId(roleId)
                    .then(
                        if (restoredMenuIds.isNotEmpty()) {
                            val entities = restoredMenuIds.map { SysRoleMenuEntity(roleId = roleId, menuId = it) }
                            roleMenuRepository.saveAll(entities).then()
                        } else Mono.empty()
                    )

                updateAction
                    .then(recordPermissionHistory(roleId, "Rollback to ver ${history.id}", history.snapshot))
                    .then(clearUserRoutersByRoleId(roleId))
            }
    }

    // 【新增】查询角色实体历史 (名称/编码)
    fun getHistory(roleId: Long): Flux<GatewayConfigHistoryEntity> {
        return historyRepository.findByConfigTypeAndConfigId(CONFIG_TYPE, roleId.toString())
            .sort(Comparator.comparing(GatewayConfigHistoryEntity::createTime).reversed())
    }

    // 【新增】查询角色权限历史 (菜单关联)
    fun getPermissionHistory(roleId: Long): Flux<GatewayConfigHistoryEntity> {
        return historyRepository.findByConfigTypeAndConfigId(CONFIG_TYPE_PERM, roleId.toString())
            .sort(Comparator.comparing(GatewayConfigHistoryEntity::createTime).reversed())
    }


    // 【新增】快照记录私有方法
    private fun recordHistory(
        id: String,
        description: String,
        entity: SysRoleEntity
    ): Mono<GatewayConfigHistoryEntity> {
        val snapshot = objectMapper.writeValueAsString(entity)
        val operator = SecurityUtils.getCurrentUsername().defaultIfEmpty("SYSTEM")

        return operator.flatMap { user ->
            historyRepository.save(
                GatewayConfigHistoryEntity(
                    configType = CONFIG_TYPE,
                    configId = id,
                    snapshot = snapshot,
                    operator = user,
                    description = description
                )
            )
        }
    }

    // 【新增】角色权限快照记录私有方法
    private fun recordPermissionHistory(
        roleId: Long,
        description: String,
        snapshotJson: String
    ): Mono<GatewayConfigHistoryEntity> {

        val operator = SecurityUtils.getCurrentUsername().defaultIfEmpty("SYSTEM")

        return operator.flatMap { user ->
            historyRepository.save(
                GatewayConfigHistoryEntity(
                    configType = CONFIG_TYPE_PERM,
                    configId = roleId.toString(),
                    snapshot = snapshotJson,
                    operator = user,
                    description = description
                )
            )
        }
    }
}