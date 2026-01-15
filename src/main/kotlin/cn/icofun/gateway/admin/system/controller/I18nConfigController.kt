package cn.icofun.gateway.admin.system.controller

import cn.icofun.gateway.infra.i18n.I18nMessageUtils
import cn.icofun.gateway.infra.model.StandardApiResponse
import cn.icofun.gateway.admin.monitor.model.entity.GatewayConfigHistoryEntity
import cn.icofun.gateway.admin.system.entity.SysI18nMessageEntity
import cn.icofun.gateway.runtime.observability.audit.LogOperation
import cn.icofun.gateway.admin.system.service.I18nMessageService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/actuator/gateway/i18n")
class I18nConfigController(
    private val i18nService: I18nMessageService,
    private val i18nMessageUtils: I18nMessageUtils
) {

    @GetMapping("/list")
    fun list(): Mono<StandardApiResponse<List<SysI18nMessageEntity>>> {
        return i18nService.findAll()
            .collectList()
            .map { StandardApiResponse. success(it) }
    }

    @LogOperation(module = "config.i18n", description = "Save I18n Message")
    @PostMapping
    fun save(
        @RequestBody entity: SysI18nMessageEntity,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return i18nService.save(entity)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                Mono.just(StandardApiResponse. success(msg))
            }
    }

    @LogOperation(module = "config.i18n", description = "Delete I18n Message")
    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: Long,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return i18nService.delete(id)
            .then(Mono.defer {
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                Mono.just(StandardApiResponse. success(msg))
            })
    }

    @LogOperation(module = "config.i18n", description = "Reload I18n Cache")
    @PostMapping("/reload")
    fun reload(exchange: ServerWebExchange): Mono<StandardApiResponse<String>> {
        return i18nService.reloadCache()
            .thenReturn(
                StandardApiResponse. success(
                    i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                )
            )
    }

    @LogOperation(module = "config.i18n", description = "Batch Import I18n Messages")
    @PostMapping("/batch")
    fun saveBatch(
        @RequestBody entities: List<SysI18nMessageEntity>,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return i18nService.saveBatch(entities)
            .thenReturn(
                StandardApiResponse. success(
                    i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                )
            )
    }

    @LogOperation(module = "config.i18n", description = "Query I18n History")
    @GetMapping("/{id}/history")
    fun getHistory(@PathVariable id: Long): Mono<StandardApiResponse<List<GatewayConfigHistoryEntity>>> {
        return i18nService.getHistory(id)
            .collectList()
            .map { StandardApiResponse. success(it) }
    }

    @LogOperation(module = "config.i18n", description = "Rollback I18n Message")
    @PostMapping("/history/{historyId}/rollback")
    fun rollback(@PathVariable historyId: Long, exchange: ServerWebExchange): Mono<StandardApiResponse<String>> {
        return i18nService.rollback(historyId)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                Mono.just(StandardApiResponse. success(msg))
            }
    }
}