package cn.icofun.gateway.service

import cn.icofun.gateway.route.RedisRouteDefinitionRepository
import org.springframework.cloud.gateway.event.RefreshRoutesEvent
import org.springframework.cloud.gateway.route.RouteDefinition
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class DynamicRouteService(
    private val repository: RedisRouteDefinitionRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {

    fun getAll() = repository.routeDefinitions

    fun add(routeDefinition: RouteDefinition): Mono<Void>? {
        return repository.save(Mono.just(routeDefinition))
            .doOnSuccess {
                eventPublisher.publishEvent(RefreshRoutesEvent(this))
            }
    }

    fun delete(id: String): Mono<Void>? {
        return repository.delete(Mono.just(id))
            .doOnSuccess {
                eventPublisher.publishEvent(RefreshRoutesEvent(this))
            }
    }
}