package cn.icofun.gateway.admin.system.service

import cn.icofun.gateway.infra.exception.BusinessException
import cn.icofun.gateway.admin.system.model.domain.MenuNode
import cn.icofun.gateway.admin.monitor.model.entity.GatewayConfigHistoryEntity
import cn.icofun.gateway.admin.system.entity.SysMenuEntity
import cn.icofun.gateway.admin.monitor.repository.GatewayConfigHistoryRepository
import cn.icofun.gateway.admin.system.repository.SysMenuRepository
import cn.icofun.gateway.admin.system.repository.SysRoleMenuRepository
import cn.icofun.gateway.admin.system.repository.SysUserRoleRepository
import cn.icofun.gateway.infra.utils.SecurityUtils
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Service
class MenuService(
    private val menuRepository: SysMenuRepository,
    private val roleMenuRepository: SysRoleMenuRepository,
    private val historyRepository: GatewayConfigHistoryRepository,
    private val objectMapper: ObjectMapper,
    private val userRoleRepository: SysUserRoleRepository,
    private val authService: AuthService,
    private val redisTemplate: ReactiveStringRedisTemplate
) {
    private val CONFIG_TYPE = "SYSTEM_MENU"

    fun findAll(): Flux<MenuNode> = menuRepository.findAll().map {
        MenuNode(
            id = it.id, parentId = it.parentId, title = it.title,
            path = it.path, component = it.component, permCode = it.permCode,
            icon = it.icon, type = it.type, sortOrder = it.sortOrder
        )
    }

    @Transactional
    fun save(menu: MenuNode): Mono<SysMenuEntity> {
        if (menu.title.isBlank()) {
            return Mono.error(BusinessException(400, "menu.title.missing"))
        }
        if (menu.path.isBlank()) {
            return Mono.error(BusinessException(400, "menu.path.missing"))
        }

        val entity = SysMenuEntity(
            id = menu.id, parentId = menu.parentId ?: 0L, title = menu.title,
            path = menu.path, component = menu.component, permCode = menu.permCode,
            icon = menu.icon, type = menu.type, sortOrder = menu.sortOrder
        )

        return if (entity.id != null) {
            menuRepository.findById(entity.id)
                .flatMap { existing ->
                    if (existing.parentId == entity.parentId &&
                        existing.title == entity.title &&
                        existing.path == entity.path &&
                        existing.component == entity.component &&
                        existing.permCode == entity.permCode &&
                        existing.icon == entity.icon &&
                        existing.type == entity.type &&
                        existing.sortOrder == entity.sortOrder
                    ) {
                        return@flatMap Mono.just(existing) // 无变化直接中止，不写审计，不调DB
                    }
                    // 【可视化增强】记录 Before 快照（包含父菜单名）
                    buildMenuSnapshot(existing).flatMap { beforeSnap ->
                        recordHistory(existing.id.toString(), "Update Menu (Before)", beforeSnap)
                            .then(menuRepository.save(entity.copy(id = existing.id)))
                            .flatMap { saved ->
                                clearUserRoutersByMenuId(saved.id!!).subscribe()
                                // 记录 After 快照
                                buildMenuSnapshot(saved).flatMap { afterSnap ->
                                    recordHistory(saved.id.toString(), "Save Menu (After)", afterSnap)
                                        .thenReturn(saved)
                                }
                            }
                    }
                }
                .switchIfEmpty(
                    // 处理传了 ID 但数据库不存在的极端情况（视为新增）
                    menuRepository.save(entity).flatMap { saved ->
                        buildMenuSnapshot(saved).flatMap { snap ->
                            recordHistory(saved.id.toString(), "Create Menu (After)", snap)
                                .thenReturn(saved)
                        }
                    }
                )
        } else {
            menuRepository.save(entity).flatMap { saved ->
                buildMenuSnapshot(saved).flatMap { snap ->
                    recordHistory(saved.id.toString(), "Create Menu (After)", snap)
                        .thenReturn(saved)
                }
            }
        }
    }

    @Transactional
    fun delete(id: Long): Mono<Void> {
        if (id <= 0) {
            return Mono.error(BusinessException(400, "sys.arg.id.invalid"))
        }
        return menuRepository.findById(id)
            .flatMap { existing ->
                // 【适配修改】delete 时也需要调用 buildMenuSnapshot 生成可视化快照
                buildMenuSnapshot(existing).flatMap { snap ->
                    recordHistory(existing.id.toString(), "Delete Menu", snap)
                        .then(roleMenuRepository.deleteByMenuId(id))
                        .then(menuRepository.deleteById(id))
                }
            }
            .then(clearUserRoutersByMenuId(id))
    }

    // 【新增】菜单回滚功能
    @Transactional
    fun rollback(historyId: Long): Mono<SysMenuEntity> {
        return historyRepository.findById(historyId)
            .switchIfEmpty(Mono.error(BusinessException(404, "sys.config.history.not_found")))
            .flatMap { history ->
                if (history.configType != CONFIG_TYPE) {
                    return@flatMap Mono.error(BusinessException(400, "sys.config.type.mismatch"))
                }

                val entity = objectMapper.readValue(history.snapshot, SysMenuEntity::class.java)

                menuRepository.save(entity)
                    .flatMap { saved ->
                        // 【适配修改】回滚记录也使用 buildMenuSnapshot
                        buildMenuSnapshot(saved).flatMap { snap ->
                            recordHistory(saved.id.toString(), "Rollback to ver ${history.id}", snap)
                                .thenReturn(saved)
                        }
                    }
            }
    }

    // 【新增】查询历史
    fun getHistory(menuId: Long): Flux<GatewayConfigHistoryEntity> {
        return historyRepository.findByConfigTypeAndConfigId(CONFIG_TYPE, menuId.toString())
            .sort(Comparator.comparing(GatewayConfigHistoryEntity::createTime).reversed())
    }

    // 【新增】快照记录私有方法
    private fun recordHistory(
        id: String,
        description: String,
        snapshotJson: String
    ): Mono<GatewayConfigHistoryEntity> {
        val operator = SecurityUtils.getCurrentUsername().defaultIfEmpty("SYSTEM")
        return operator.flatMap { user ->
            historyRepository.save(
                GatewayConfigHistoryEntity(
                    configType = CONFIG_TYPE,
                    configId = id,
                    snapshot = snapshotJson, // 直接使用传入的 JSON 字符串
                    operator = user,
                    description = description
                )
            )
        }
    }

    @Transactional
    fun clearUserRoutersByMenuId(menuId: Long): Mono<Void> {
        // 1. 找出所有关联该菜单的角色ID (Menu -> Role)
        return roleMenuRepository.findAll()
            .filter { it.menuId == menuId }
            .map { it.roleId }
            .distinct()
            .collectList()
            // 2. 对于每个角色ID，找到所有关联的用户ID (Role -> User)
            .flatMapMany { roleIds ->
                if (roleIds.isEmpty()) Flux.empty()
                else userRoleRepository.findAll() // 注意：这里需要一个 findAll 或 findByRoleIdIn 的方法
                    .filter { roleIds.contains(it.roleId) }
                    .map { it.userId }
                    .distinct()
            }
            // 3. 找出所有用户的用户名
            .flatMap { userId -> authService.getUserEntityById(userId) } // 需要 AuthService 提供 getUserById 方法
            .map { user -> "gateway:user:routers:${user.username}" }
            .collectList()
            // 4. 从 Redis 中删除缓存 Key
            .flatMap { keys ->
                if (keys.isNotEmpty()) {
                    redisTemplate.delete(Flux.fromIterable(keys)).then()
                } else {
                    Mono.empty()
                }
            }
            .then()
    }

    /**
     * 【新增】构建增强型快照，将 parentId 转为 菜单名(ID)
     */
    private fun buildMenuSnapshot(entity: SysMenuEntity): Mono<String> {
        val snapshotMap = mutableMapOf<String, Any?>()
        snapshotMap["id"] = entity.id
        snapshotMap["名称"] = entity.title
        snapshotMap["路径"] = entity.path
        snapshotMap["组件"] = entity.component
        snapshotMap["权限码"] = entity.permCode
        snapshotMap["图标"] = entity.icon
        snapshotMap["类型"] = entity.type
        snapshotMap["排序"] = entity.sortOrder

        return if (entity.parentId == 0L) {
            snapshotMap["父级菜单"] = "无(0)"
            Mono.just(objectMapper.writeValueAsString(snapshotMap))
        } else {
            menuRepository.findById(entity.parentId)
                .map { "${it.title}(${it.id})" }
                .defaultIfEmpty("未知(${entity.parentId})")
                .map {
                    snapshotMap["父级菜单"] = it
                    objectMapper.writeValueAsString(snapshotMap)
                }
        }
    }
}