package cn.icofun.gateway.controller

import cn.icofun.gateway.model.dto.StandardApiResponse
import cn.icofun.gateway.service.DynamicRouteService
import org.springframework.cloud.gateway.route.RouteDefinition
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/actuator/gateway/routes/dynamic")
class RouteController(
    private val dynamicRouteService: DynamicRouteService
) {

    @GetMapping
    fun getAll(): Mono<StandardApiResponse<List<RouteDefinition>>> {
        return dynamicRouteService.getAll()
            .collectList()
            .map { StandardApiResponse.success(it) }
    }

    @PostMapping
    fun add(@RequestBody routeDefinition: RouteDefinition): Mono<StandardApiResponse<String>>? {
        return dynamicRouteService.add(routeDefinition)
            ?.thenReturn(StandardApiResponse.success("Route added successfully"))
    }

    @PutMapping
    fun update(@RequestBody routeDefinition: RouteDefinition): Mono<StandardApiResponse<String>>? {
        return dynamicRouteService.add(routeDefinition)
            ?.thenReturn(StandardApiResponse.success("Route updated successfully"))
    }

    // 删除路由
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): Mono<StandardApiResponse<String>>? {
        return dynamicRouteService.delete(id)
            ?.thenReturn(StandardApiResponse.success("Route deleted successfully"))
    }
}