package cn.icofun.gateway.admin.system.service

import cn.icofun.gateway.infra.exception.BusinessException
import cn.icofun.gateway.admin.system.model.dto.LoginRequest
import cn.icofun.gateway.admin.system.model.domain.RoleInfo
import cn.icofun.gateway.admin.system.model.domain.UserInfo
import cn.icofun.gateway.admin.monitor.model.entity.GatewayConfigHistoryEntity
import cn.icofun.gateway.admin.system.entity.SysMenuEntity
import cn.icofun.gateway.admin.system.entity.SysUserEntity
import cn.icofun.gateway.admin.system.entity.SysUserRoleEntity
import cn.icofun.gateway.admin.route.model.vo.MetaVo
import cn.icofun.gateway.admin.system.model.vo.TokenVo
import cn.icofun.gateway.admin.monitor.repository.GatewayConfigHistoryRepository
import cn.icofun.gateway.admin.route.model.vo.RouterVo
import cn.icofun.gateway.admin.system.repository.SysMenuRepository
import cn.icofun.gateway.admin.system.repository.SysRoleMenuRepository
import cn.icofun.gateway.admin.system.repository.SysRoleRepository
import cn.icofun.gateway.admin.system.repository.SysUserRepository
import cn.icofun.gateway.admin.system.repository.SysUserRoleRepository
import cn.icofun.gateway.runtime.observability.audit.LogOperation
import cn.icofun.gateway.infra.utils.JwtUtils
import cn.icofun.gateway.infra.utils.SecurityUtils
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.security.MessageDigest
import java.time.Duration

@Service
class AuthService(
    private val userRepository: SysUserRepository,
    private val menuRepository: SysMenuRepository,
    private val roleRepository: SysRoleRepository,
    private val userRoleRepository: SysUserRoleRepository,
    private val roleMenuRepository: SysRoleMenuRepository,
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val jwtUtils: JwtUtils,
    private val passwordEncoder: PasswordEncoder,
    private val historyRepository: GatewayConfigHistoryRepository
) {
    @Value($$"${jwt.expiration:3600000}")
    private val accessExpiration: Long = 3600000

    @Value($$"${jwt.refresh-expiration:604800000}") // 默认7天
    private val refreshExpiration: Long = 604800000

    private val CONFIG_TYPE = "SYSTEM_USER"

    fun login(request: LoginRequest): Mono<TokenVo> {
        return userRepository.findByUsername(request.username)
            .filter { it.enabled && passwordEncoder.matches(request.password, it.password) }
            .flatMap { user ->
                createTokenVo(user.username)
            }.switchIfEmpty(Mono.error(BusinessException(401, "auth.login.error")))
    }

    fun refreshToken(refreshToken: String): Mono<TokenVo> {
        return jwtUtils.parseTokenMono(refreshToken)
            .map { claims ->
                claims.subject
            }.onErrorResume { _ ->
                Mono.error(BusinessException(401, "auth.token.invalid"))
            }
            .flatMap { username ->
                val redisKey = getRefreshRedisKey(username, refreshToken)
                redisTemplate.hasKey(redisKey)
                    .flatMap { exists ->
                        if (!exists) {
                            Mono.error(BusinessException(401, "auth.token.revoked_or_reused"))
                        } else {
                            redisTemplate.delete(redisKey)
                                .then(
                                    createTokenVo(username)
                                )
                        }
                    }
                redisTemplate.hasKey("refresh_token:$username:$refreshToken")
                createTokenVo(username)
            }
    }

    private fun createTokenVo(username: String): Mono<TokenVo> {
        val accessToken = jwtUtils.generateToken(username, accessExpiration) // 需修改 JwtUtils 支持传入过期时间
        val refreshToken = jwtUtils.generateToken(username, refreshExpiration)
        val grafanaToken = jwtUtils.generateToken(username, refreshExpiration)
        val redisKey = getRefreshRedisKey(username, refreshToken)
        return redisTemplate.opsForValue()
            .set(redisKey, "1", Duration.ofMillis(refreshExpiration))
            .map {
                TokenVo(accessToken, refreshToken, accessExpiration / 1000, grafanaToken)
            }

    }

    @LogOperation(module = "用户管理", description = "新增/修改用户")
    @Transactional
    fun saveUser(user: UserInfo): Mono<SysUserEntity> {
        val clearCache = { username: String ->
            val cacheKey = "gateway:user:routers:$username"
            redisTemplate.delete(cacheKey).subscribe() // 异步删除，不阻塞主流程
        }

        return if (user.id != null) {
            userRepository.findById(user.id)
                .flatMap { existing ->
                    userRoleRepository.findByUserId(existing.id!!)
                        .map { it.roleId }
                        .collectList()
                        .flatMap { currentRoleIds ->
                            val passwordChanged = user.password?.isNotBlank()
                            val rolesChanged = user.roleIds != null && user.roleIds.sorted() != currentRoleIds.sorted()
                            val baseInfoChanged = existing.username != user.username || existing.enabled != user.enabled

                            if (!passwordChanged!! && !rolesChanged && !baseInfoChanged) {
                                // 【极致优化】没有任何变化，直接返回，不写审计记录
                                return@flatMap Mono.just(existing)
                            }

                            recordAudit(existing.id, "Update User (Before)", existing, currentRoleIds)
                                .then(Mono.defer {
                                    val updatedEntity = existing.copy(
                                        username = user.username!!,
                                        password = if (passwordChanged) passwordEncoder.encode(user.password)!! else existing.password,
                                        enabled = user.enabled
                                    )
                                    userRepository.save(updatedEntity)
                                })
                                .flatMap { saved ->
                                    // 4. 更新角色并记录更新后快照 (After)
                                    val updateRolesTask = if (user.roleIds != null) {
                                        updateUserRoles(saved.id!!, user.roleIds)
                                    } else Mono.empty()

                                    updateRolesTask.then(
                                        recordAudit(saved.id!!, "Update User (After)", saved, user.roleIds)
                                    ).then(Mono.fromRunnable<Unit> { clearCache(saved.username) })
                                        .thenReturn(saved)
                                }
                        }
                }
                .switchIfEmpty(Mono.error(BusinessException(404, "auth.user.not_found")))
        } else {
            if (user.username!!.isBlank() || user.password!!.isBlank()) {
                return Mono.error(BusinessException(400, "auth.user.pwd.missing"))
            }

            userRepository.findByUsername(user.username)
                .flatMap<SysUserEntity> {
                    Mono.error(
                        BusinessException(
                            code = 400,
                            message = "auth.user.exists",
                            args = arrayOf(user.username)
                        )
                    )
                }
                .switchIfEmpty(
                    userRepository.save(
                        SysUserEntity(
                            username = user.username,
                            password = passwordEncoder.encode(user.password)!!,
                            enabled = user.enabled,
                            createTime = user.createTime
                        )
                    ).flatMap { saved ->
                        updateUserRoles(saved.id!!, user.roleIds)
                            .then(recordAudit(saved.id, "Create User (After)", saved, user.roleIds))
                            .thenReturn(saved)
                    }
                )
        }
    }

    private fun updateUserRoles(userId: Long, roleIds: List<Long>?): Mono<Void> {
        return userRoleRepository.deleteByUserId(userId)
            .then(
                if (!roleIds.isNullOrEmpty()) {
                    val entities = roleIds.map { SysUserRoleEntity(userId = userId, roleId = it) }
                    userRoleRepository.saveAll(entities).then()
                } else Mono.empty()
            )
    }

    @Transactional
    fun deleteUser(id: Long): Mono<Void> {
        return userRepository.findById(id)
            .flatMap { existing ->
                recordAudit(existing.id!!, "Delete User: ${existing.username}").subscribe()
                userRoleRepository.deleteByUserId(id)
                    .then(userRepository.deleteById(id))
            }
            .then()
    }

    fun getUserById(id: Long): Mono<UserInfo> {
        return userRepository.findById(id)
            .flatMap { userEntity ->
                fetchUserRoles(userEntity)
            }
    }

    fun getUser(username: String): Mono<UserInfo> {
        return userRepository.findByUsername(username)
            .flatMap { userEntity ->
                fetchUserRoles(userEntity)
            }
    }

    fun getUserRouters(username: String): Mono<List<RouterVo>> {
        val cacheKey = "gateway:user:routers:$username"

        return redisTemplate.opsForValue().get(cacheKey)
            .flatMap { json ->
                try {
                    val type = object : TypeReference<List<RouterVo>>() {}
                    Mono.just(objectMapper.readValue(json, type))
                } catch (_: Exception) {
                    Mono.empty()
                }
            }
            .switchIfEmpty(
                queryMenusFromDb(username)
                    .collectList()
                    .map { menus -> buildRouterTree(menus) }
                    .flatMap { routers ->
                        val json = objectMapper.writeValueAsString(routers)
                        redisTemplate.opsForValue().set(cacheKey, json, Duration.ofMinutes(30))
                            .thenReturn(routers)
                    }
            )
    }

    fun getAllUsers(): Flux<UserInfo> {
        return userRepository.findAll()
            .flatMap { entity ->
                userRoleRepository.findByUserId(entity.id!!)
                    .map { it.roleId }
                    .collectList()
                    .flatMap { roleIds ->
                        if (roleIds.isEmpty()) Mono.just(emptyList())
                        else roleRepository.findAllById(roleIds).collectList()
                    }
                    .map { roles ->
                        val sysRoles = roles.map { RoleInfo(it.id, it.name, it.code) }
                        UserInfo(
                            id = entity.id,
                            username = entity.username,
                            password = entity.password,
                            role = if (roles.isNotEmpty()) roles[0].code else "USER",
                            roles = sysRoles,
                            enabled = entity.enabled,
                            createTime = entity.createTime,
                        )
                    }
            }
    }

    private fun queryMenusFromDb(username: String): Flux<SysMenuEntity> {
        if (username == "admin") {
            return menuRepository.findAllMenus()
        }
        return userRepository.findByUsername(username)
            .flatMapMany { user ->
                userRoleRepository.findByUserId(user.id!!)
                    .map { it.roleId }
                    .collectList()
                    .flatMapMany { roleIds ->
                        if (roleIds.isEmpty()) Flux.empty()
                        else roleMenuRepository.findAll()
                            .filter { roleIds.contains(it.roleId) }
                            .map { it.menuId }
                            .collectList()
                            .flatMapMany { menuIds ->
                                if (menuIds.isEmpty()) Flux.empty()
                                else menuRepository.findAllById(menuIds)
                            }
                    }
            }.distinct()
    }

    private fun buildRouterTree(menus: List<SysMenuEntity>): List<RouterVo> {
        val routerList = mutableListOf<RouterVo>()
        val rootMenus = menus.filter { it.parentId == 0L }

        for (menu in rootMenus) {
            val children = getChildren(menu.id!!, menus)
            routerList.add(convertMenuToRouter(menu, children))
        }
        return routerList
    }

    private fun getChildren(parentId: Long, allMenus: List<SysMenuEntity>): List<RouterVo> {
        val children = allMenus.filter { it.parentId == parentId }
        return children.map { menu ->
            convertMenuToRouter(menu, getChildren(menu.id!!, allMenus))
        }
    }

    private fun convertMenuToRouter(menu: SysMenuEntity, children: List<RouterVo>): RouterVo {
        // 确保 Layout 组件路径正确
        val component = if (menu.parentId == 0L && menu.component == null) "Layout" else menu.component ?: "Layout"

        val isExternal =
            menu.path.startsWith("http://") || menu.path.startsWith("https://") || menu.path.startsWith("mailto:")

        val finalPath = if (menu.parentId == 0L && !menu.path.startsWith("/") && !isExternal) {
            "/" + menu.path
        } else {
            menu.path
        }

        return RouterVo(
            name = if (isExternal) capitalize(menu.path) else capitalize(menu.path.replace("/", "")),
            path = finalPath,
            component = component,
            meta = MetaVo(title = menu.title, icon = menu.icon),
            children = children.ifEmpty { null }
        )
    }

    private fun capitalize(str: String) = if (str.isNotEmpty()) str.replaceFirstChar { it.uppercase() } else ""

    private fun fetchUserRoles(userEntity: SysUserEntity): Mono<UserInfo> {
        return userRoleRepository.findByUserId(userEntity.id!!)
            .map { it.roleId }
            .collectList()
            .flatMap { roleIds ->
                if (roleIds.isEmpty()) Mono.just(emptyList())
                else roleRepository.findAllById(roleIds).collectList()
            }
            .map { roles ->
                val sysRoles = roles.map { RoleInfo(it.id, it.name, it.code) }
                UserInfo(
                    id = userEntity.id,
                    username = userEntity.username,
                    password = "", // 密码不回显
                    role = if (roles.isNotEmpty()) roles[0].code else "USER",
                    roles = sysRoles,
                    enabled = userEntity.enabled,
                    createTime = userEntity.createTime,
                )
            }
    }

    // 【新增】通用的用户操作审计快照记录方法
    private fun recordAudit(
        userId: Long,
        desc: String,
        entity: SysUserEntity? = null,
        roleIds: List<Long>? = null // 新增参数
    ): Mono<GatewayConfigHistoryEntity> {
        val operatorMono = SecurityUtils.getCurrentUsername().defaultIfEmpty("SYSTEM")

        // 2. 构建快照 (如果有实体，则解析角色名；如果没有，直接存描述)
        val snapshotMono: Mono<String> = if (entity != null) {
            val snapshotMap = mutableMapOf<String, Any?>()
            snapshotMap["id"] = entity.id
            snapshotMap["username"] = entity.username
            snapshotMap["enabled"] = entity.enabled
            snapshotMap["password"] = "******" // 脱敏
            snapshotMap["createTime"] = entity.createTime

            if (roleIds.isNullOrEmpty()) {
                snapshotMap["roles"] = emptyList<String>()
                Mono.just(objectMapper.writeValueAsString(snapshotMap))
            } else {
                // 关键点：根据 ID 查询角色中文名称
                roleRepository.findAllById(roleIds)
                    .map { it.name } // 假设 SysRoleEntity 中 name 是中文名 (如"超级管理员"), code 是英文 (如"ROLE_ADMIN")
                    .collectList()
                    .map { roleNames ->
                        snapshotMap["roles"] = roleNames // 存入 ["超级管理员", "运维人员"]
                        // snapshotMap["roleIds"] = roleIds // 如果还需要保留ID，可以解开这行
                        objectMapper.writeValueAsString(snapshotMap)
                    }
            }
        } else {
            Mono.just(desc)
        }

        return Mono.zip(operatorMono, snapshotMono)
            .flatMap { tuple ->
                val operator = tuple.t1
                val snapshotJson = tuple.t2

                historyRepository.save(
                    GatewayConfigHistoryEntity(
                        configType = CONFIG_TYPE,
                        configId = userId.toString(),
                        snapshot = snapshotJson,
                        operator = operator,
                        description = desc
                    )
                )
            }
    }

    fun getUserHistory(userId: Long): Flux<GatewayConfigHistoryEntity> {
        return historyRepository.findByConfigTypeAndConfigId(CONFIG_TYPE, userId.toString())
            .sort(Comparator.comparing(GatewayConfigHistoryEntity::createTime).reversed())
    }

    fun getUserEntityById(id: Long): Mono<SysUserEntity> {
        return userRepository.findById(id)
    }

    private fun getRefreshRedisKey(username: String, token: String): String {
        val tokenHash = md5(token)
        return "auth:refresh:$username:$tokenHash"
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}