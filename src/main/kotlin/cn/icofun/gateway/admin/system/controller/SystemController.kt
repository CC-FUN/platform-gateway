package cn.icofun.gateway.admin.system.controller

import cn.icofun.gateway.infra.exception.BusinessException
import cn.icofun.gateway.infra.i18n.I18nMessageUtils
import cn.icofun.gateway.admin.system.model.dto.LoginRequest
import cn.icofun.gateway.infra.model.StandardApiResponse
import cn.icofun.gateway.admin.system.model.domain.UserInfo
import cn.icofun.gateway.admin.monitor.model.entity.GatewayConfigHistoryEntity
import cn.icofun.gateway.admin.system.model.vo.TokenVo
import cn.icofun.gateway.runtime.observability.audit.LogOperation
import cn.icofun.gateway.admin.system.service.AuthService
import cn.icofun.gateway.admin.monitor.service.GrafanaSyncService
import cn.icofun.gateway.admin.route.model.vo.RouterVo
import cn.icofun.gateway.admin.system.service.RoleService
import cn.icofun.gateway.infra.utils.SecurityUtils
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/sys")
class SystemController(
    private val authService: AuthService,
    private val roleService: RoleService,
    private val i18nMessageUtils: I18nMessageUtils,
    private val grafanaSyncService: GrafanaSyncService
) {

    @LogOperation(module = "auth", description = "User Login")
    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): Mono<StandardApiResponse<TokenVo>> {
        return authService.login(request)
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "auth", description = "Refresh Token")
    @PostMapping("/refresh")
    fun refreshToken(@RequestParam refreshToken: String): Mono<StandardApiResponse<TokenVo>> {
        return authService.refreshToken(refreshToken)
            .map { StandardApiResponse.success(it) }
    }

    @GetMapping("/user/info")
    fun getUserInfo(): Mono<StandardApiResponse<UserInfo>> {
        return SecurityUtils.getCurrentUsername()
            .flatMap { username ->
                authService.getUser(username)
            }
            .map { StandardApiResponse.success(it) }
    }

    @GetMapping("/user/routers")
    fun getUserRouters(): Mono<StandardApiResponse<List<RouterVo>>> {
        return SecurityUtils.getCurrentUsername()
            .flatMap { username ->
                authService.getUserRouters(username)
            }
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "sys.user", description = "Save User")
    @PostMapping("/user/save")
    fun saveUser(
        @RequestBody userToSave: UserInfo,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        val rawPassword = userToSave.password

        return SecurityUtils.getCurrentUsername()
            .flatMap { currentUsername ->
                authService.getUser(currentUsername)
            }
            .flatMap { operator ->
                val operation = if (userToSave.id != null) {
                    authService.getUserById(userToSave.id)
                        .flatMap { targetUser ->
                            if (targetUser.isSuperAdmin() && !operator.isSuperAdmin()) {
                                Mono.error(BusinessException(403, "auth.admin.modify.denied"))
                            } else {
                                checkRoleEscalation(operator, userToSave.roleIds)
                                    .then(authService.saveUser(userToSave))
                            }
                        }
                        .switchIfEmpty(Mono.error(BusinessException(404, "user.not.found")))
                } else {
                    checkRoleEscalation(operator, userToSave.roleIds)
                        .then(authService.saveUser(userToSave))
                        .flatMap { savedUser ->
                            grafanaSyncService.createGrafanaUser(
                                userToSave.username ?: "",
                                "", // 如果没填邮箱，Service 里会处理默认值
                                rawPassword ?: ""
                            ).then(Mono.justOrEmpty(savedUser))
                        }
                }

                operation.flatMap {
                    val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                    Mono.just(StandardApiResponse.success(msg))
                }
            }
    }

    @LogOperation(module = "sys.user", description = "Query User List")
    @GetMapping("/user/list")
    fun listUsers(): Mono<StandardApiResponse<List<UserInfo>>> {
        return SecurityUtils.getCurrentUsername()
            .flatMap { currentUsername ->
                authService.getUser(currentUsername)
            }
            .flatMap {
                authService.getAllUsers()
                    .collectList()
                    .map { StandardApiResponse.success(it) }

            }
    }

    @LogOperation(module = "sys.user", description = "Delete User")
    @DeleteMapping("/user/delete")
    fun deleteUser(
        @RequestParam id: Long,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return SecurityUtils.getCurrentUsername()
            .flatMap { currentUsername ->
                authService.getUser(currentUsername)
            }
            .flatMap { operator ->
                authService.getUserById(id)
                    .switchIfEmpty(Mono.error(BusinessException(404, "user.not.found")))
                    .flatMap { targetUser ->
                        if (targetUser.isSuperAdmin() && !operator.isSuperAdmin()) {
                            Mono.error(BusinessException(403, "auth.admin.delete.denied"))
                        } else {
                            authService.deleteUser(id).flatMap {
                                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                                Mono.just(StandardApiResponse.success(msg))
                            }
                        }
                    }
            }
    }

    // 【新增】查询用户审计历史
    @LogOperation(module = "sys.user", description = "Query User Audit History")
    @GetMapping("/user/{id}/history")
    fun getUserHistory(@PathVariable id: Long): Mono<StandardApiResponse<List<GatewayConfigHistoryEntity>>> {
        return authService.getUserHistory(id)
            .collectList()
            .map { StandardApiResponse.success(it) }
    }

    // 辅助方法：检查角色越权
    private fun checkRoleEscalation(operator: UserInfo, roleIds: List<Long>?): Mono<Void> {
        if (operator.isSuperAdmin()) return Mono.empty() // 超管不受限
        if (roleIds.isNullOrEmpty()) return Mono.empty()

        return roleService.findAllByIds(roleIds)
            .any { "SUPER_ADMIN".equals(it.code, ignoreCase = true) }
            .flatMap { hasSuperAdminRole ->
                if (hasSuperAdminRole) {
                    Mono.error(BusinessException(403, "auth.role.escalation.denied"))
                } else {
                    Mono.empty()
                }
            }
    }


}