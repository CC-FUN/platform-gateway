package cn.icofun.gateway.controller

import cn.icofun.gateway.annotation.LogOperation
import cn.icofun.gateway.i18n.I18nMessageUtils
import cn.icofun.gateway.model.StandardApiResponse
import cn.icofun.gateway.model.SysMenu
import cn.icofun.gateway.model.entity.GatewayConfigHistoryEntity
import cn.icofun.gateway.service.MenuService
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/sys/menu")
class MenuController(
    private val menuService: MenuService,
    private val i18nMessageUtils: I18nMessageUtils
) {

    @LogOperation(module = "sys.menu", description = "Query Menu List")
    @GetMapping("/list")
    fun list(): Mono<StandardApiResponse<List<SysMenu>>> {
        return menuService.findAll()
            .collectList()
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "sys.menu", description = "Save Menu")
    @PostMapping("/save")
    fun save(
        @RequestBody menu: SysMenu,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return menuService.save(menu).flatMap {
            // [改造] 使用 op.success
            val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
            Mono.just(StandardApiResponse.success(msg))
        }
    }

    @LogOperation(module = "sys.menu", description = "Delete Menu")
    @DeleteMapping("/delete/{id}")
    fun delete(
        @PathVariable id: Long,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return menuService.delete(id).flatMap {
            val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
            Mono.just(StandardApiResponse.success(msg))
        }
    }

    @LogOperation(module = "sys.menu", description = "Query Menu History")
    @GetMapping("/{id}/history")
    fun getHistory(@PathVariable id: Long): Mono<StandardApiResponse<List<GatewayConfigHistoryEntity>>> {
        return menuService.getHistory(id)
            .collectList()
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "sys.menu", description = "Rollback Menu")
    @PostMapping("/history/{historyId}/rollback")
    fun rollback(
        @PathVariable historyId: Long,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return menuService.rollback(historyId)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                Mono.just(StandardApiResponse.success("$msg (Menu ID: ${it.id})"))
            }
    }

}