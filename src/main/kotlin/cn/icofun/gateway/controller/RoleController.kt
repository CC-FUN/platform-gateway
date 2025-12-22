package cn.icofun.gateway.controller

import cn.icofun.gateway.annotation.LogOperation
import cn.icofun.gateway.exception.BusinessException
import cn.icofun.gateway.i18n.I18nMessageUtils
import cn.icofun.gateway.model.RoleMenuDto
import cn.icofun.gateway.model.StandardApiResponse
import cn.icofun.gateway.model.SysRole
import cn.icofun.gateway.model.entity.GatewayConfigHistoryEntity
import cn.icofun.gateway.service.AuthService
import cn.icofun.gateway.service.RoleService
import cn.icofun.gateway.utils.SecurityUtils
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/sys/role")
class RoleController(
    private val roleService: RoleService,
    private val authService: AuthService,
    private val i18nMessageUtils: I18nMessageUtils
) {

    @LogOperation(module = "sys.role", description = "Query Role List")
    @GetMapping("/list")
    fun list(): Mono<StandardApiResponse<List<SysRole>>> {
        return roleService.findAll().collectList().map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "sys.role", description = "Save Role")
    @PostMapping("/save")
    fun save(
        @RequestBody role: SysRole,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return SecurityUtils.getCurrentUsername()
            .flatMap { authService.getUser(it) }
            .flatMap { operator ->
                if ("SUPER_ADMIN".equals(role.code, ignoreCase = true)) {
                    if (!operator.isSuperAdmin()) {
                        return@flatMap Mono.error(
                            BusinessException(
                                403,
                                "role.admin.modify.denied"
                            )
                        )
                    }
                }

                val saveMono = if (role.id != null) {
                    roleService.findById(role.id)
                        .flatMap { existing ->
                            if ("SUPER_ADMIN".equals(existing.code, ignoreCase = true) && !operator.isSuperAdmin()) {
                                Mono.error(BusinessException(403, "role.admin.modify.denied"))
                            } else {
                                roleService.save(role)
                            }
                        }
                } else {
                    roleService.save(role)
                }

                saveMono.flatMap {
                    val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                    Mono.just(StandardApiResponse.success(msg))
                }

            }
    }

    @LogOperation(module = "sys.role", description = "Delete Role")
    @DeleteMapping("/delete")
    fun delete(
        @RequestParam id: Long,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return SecurityUtils.getCurrentUsername()
            .flatMap { authService.getUser(it) }
            .flatMap { operator ->
                roleService.findById(id)
                    .flatMap { targetRole ->
                        if ("SUPER_ADMIN".equals(targetRole.code, ignoreCase = true) && !operator.isSuperAdmin()) {
                            Mono.error(BusinessException(403, "role.admin.delete.denied"))
                        } else {
                            roleService.delete(id).flatMap {
                                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                                Mono.just(StandardApiResponse.success(msg))
                            }
                        }
                    }
            }
    }

    @LogOperation(module = "sys.role", description = "Query Role Menus")
    @GetMapping("/menus")
    fun getRoleMenus(@RequestParam roleId: Long): Mono<StandardApiResponse<List<Long>>> {
        return roleService.findMenuIdsByRoleId(roleId).map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "sys.role", description = "Save Role Menus")
    @PostMapping("/menus")
    fun saveRoleMenus(
        @RequestBody dto: RoleMenuDto,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return SecurityUtils.getCurrentUsername()
            .flatMap { authService.getUser(it) }
            .flatMap { operator ->
                roleService.findById(dto.roleId)
                    .flatMap { targetRole ->
                        if ("SUPER_ADMIN".equals(targetRole.code, ignoreCase = true) && !operator.isSuperAdmin()) {
                            Mono.error(BusinessException(403, "role.admin.perm.denied"))
                        } else {
                            roleService.saveRoleMenus(dto.roleId, dto.menuIds)
                                .flatMap {
                                    val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                                    Mono.just(StandardApiResponse.success(msg))
                                }
                        }
                    }
            }
    }

    @LogOperation(module = "sys.role", description = "Query Role History")
    @GetMapping("/{id}/history")
    fun getHistory(@PathVariable id: Long): Mono<StandardApiResponse<List<GatewayConfigHistoryEntity>>> {
        return roleService.getHistory(id)
            .collectList()
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "sys.role", description = "Query Role Permission History")
    @GetMapping("/{id}/perm-history")
    fun getPermissionHistory(@PathVariable id: Long): Mono<StandardApiResponse<List<GatewayConfigHistoryEntity>>> {
        return roleService.getPermissionHistory(id)
            .collectList()
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "sys.role", description = "Rollback Role Entity")
    @PostMapping("/history/{historyId}/rollback")
    fun rollback(@PathVariable historyId: Long, exchange: ServerWebExchange): Mono<StandardApiResponse<String>> {
        return roleService.rollback(historyId)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                Mono.just(StandardApiResponse.success("$msg (Role ID: ${it.id})"))
            }
    }

    @LogOperation(module = "sys.role", description = "Rollback Role Permissions")
    @PostMapping("/perm-history/{historyId}/rollback")
    fun rollbackPermissions(@PathVariable historyId: Long, exchange: ServerWebExchange): Mono<StandardApiResponse<String>> {
        return roleService.rollbackRoleMenus(historyId)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            }
    }
}