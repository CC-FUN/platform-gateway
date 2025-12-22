package cn.icofun.gateway.repository

import cn.icofun.gateway.model.entity.SysMenuEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux

interface SysMenuRepository : R2dbcRepository<SysMenuEntity, Long> {
    @Query(
        """
        SELECT DISTINCT m.* FROM sys.sys_menu m 
        INNER JOIN sys.sys_role_menu rm ON m.id = rm.menu_id
        INNER JOIN sys.sys_role r ON rm.role_id = r.id
        INNER JOIN sys.sys_user_role ur ON r.id = ur.role_id
        INNER JOIN sys.sys_user u ON ur.user_id = u.id
        WHERE u.username = :username AND m.type IN (0, 1)
        AND u.enabled = 1
        ORDER BY m.sort_order
    """
    )
    fun findMenusByUsername(username: String): Flux<SysMenuEntity>

    @Query(
        """
        SELECT DISTINCT m.perm_code FROM sys.sys_menu m 
        INNER JOIN sys.sys_role_menu rm ON m.id = rm.menu_id
        INNER JOIN sys.sys_user_role ur ON rm.role_id = ur.role_id
        INNER JOIN sys.sys_user u ON ur.user_id = u.id
        WHERE u.username = :username AND m.type = 2
        AND m.perm_code IS NOT NULL AND m.perm_code != ''
    """
    )
    fun findPermsByUsername(username: String): Flux<String>

    @Query(
        """
        SELECT * FROM sys.sys_menu WHERE type IN (0, 1) ORDER BY sort_order
    """
    )
    fun findAllMenus(): Flux<SysMenuEntity>

    @Query(
        """
        SELECT DISTINCT perm_code FROM sys.sys_menu WHERE type = 2 AND perm_code != ''
    """
    )
    fun findAllPerms(): Flux<String>
}