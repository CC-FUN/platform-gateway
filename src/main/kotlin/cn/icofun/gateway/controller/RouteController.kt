package cn.icofun.gateway.controller

import cn.icofun.gateway.annotation.LogOperation
import cn.icofun.gateway.i18n.I18nMessageUtils
import cn.icofun.gateway.model.StandardApiResponse
import cn.icofun.gateway.model.dto.GatewayRouteDTO
import cn.icofun.gateway.model.entity.GatewayConfigHistoryEntity
import cn.icofun.gateway.service.DynamicRouteService
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/actuator/gateway/routes/dynamic")
class RouteController(
    private val dynamicRouteService: DynamicRouteService,
    private val i18nMessageUtils: I18nMessageUtils,
) {

    @GetMapping
    fun getAll(): Mono<StandardApiResponse<List<GatewayRouteDTO>>> {
        return dynamicRouteService.getAll()
            .collectList()
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "route", description = "Save Route Config")
    @PostMapping("/save")
    fun save(
        @RequestBody routeDto: GatewayRouteDTO,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return dynamicRouteService.save(routeDto)
            .flatMap {
                // "路由已保存 (请点击发布以生效)"
                val msg = i18nMessageUtils.getMessage("route.save.success", null, exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            }
    }

    @LogOperation(module = "route", description = "Save Route Config")
    @PostMapping
    fun add(
        @RequestBody routeDto: GatewayRouteDTO,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return save(routeDto, exchange)
    }

    @LogOperation(module = "route", description = "Update Route")
    @PutMapping
    fun update(
        @RequestBody routeDto: GatewayRouteDTO,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return save(routeDto, exchange)
    }


    @LogOperation(module = "route", description = "Delete Route")
    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: String,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return dynamicRouteService.delete(id)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            }
    }

    @LogOperation(module = "route", description = "Publish Routes")
    @PostMapping("/publish")
    fun refresh(exchange: ServerWebExchange): Mono<StandardApiResponse<String>> {
        return dynamicRouteService.publishRoutes()
            .flatMap {
                val msg = i18nMessageUtils.getMessage("route.publish.success", null, exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            }
    }

    @LogOperation(module = "config.route", description = "Query Route History")
    @GetMapping("/{routeId}/history")
    fun getHistory(@PathVariable routeId: String): Mono<StandardApiResponse<List<GatewayConfigHistoryEntity>>> {
        return dynamicRouteService.getHistory(routeId)
            .collectList()
            .defaultIfEmpty(emptyList())
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "config.route", description = "Rollback Route")
    @PostMapping("/history/{historyId}/rollback")
    fun rollback(@PathVariable historyId: Long, exchange: ServerWebExchange): Mono<StandardApiResponse<String>> {
        return dynamicRouteService.rollback(historyId)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                Mono.just(StandardApiResponse.success("$msg (Route ID: ${it.id})"))
            }
    }

    @LogOperation(module = "route", description = "Deprecate API")
    @PostMapping("/{id}/deprecate")
    fun deprecate(
        @PathVariable id: String,
        @RequestParam deprecated: Boolean,
        @RequestParam(required = false) sunset: String?,
        @RequestParam(required = false) link: String?,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        val metaUpdates = mapOf(
            "deprecated" to deprecated,
            "sunset" to sunset,
            "deprecation_link" to link
        )

        return dynamicRouteService.updateMetadata(id, metaUpdates)
            .then(Mono.defer {
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            })
    }
}