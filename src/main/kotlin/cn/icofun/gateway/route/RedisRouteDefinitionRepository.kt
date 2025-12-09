package cn.icofun.gateway.route

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.route.RouteDefinition
import org.springframework.cloud.gateway.route.RouteDefinitionRepository
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Component
class RedisRouteDefinitionRepository(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper
) : RouteDefinitionRepository {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val ROUTE_KEY = "gateway:routes"

    override fun getRouteDefinitions(): Flux<RouteDefinition> {
        return redisTemplate.opsForHash<String, String>().values(ROUTE_KEY)
            .flatMap { routeJson ->
                try {
                    val routeDefinition = objectMapper.readValue<RouteDefinition>(routeJson)
                    Flux.just(routeDefinition)
                } catch (e: Exception) {
                    logger.error("Failed to parse route definition from Redis", e)
                    Flux.empty()
                }
            }
    }

    override fun save(route: Mono<RouteDefinition>): Mono<Void> {
        return route.flatMap { routeDefinition ->
            try {
                val routeId = routeDefinition.id
                val routeJson = objectMapper.writeValueAsString(routeDefinition)

                redisTemplate.opsForHash<String, String>().put(ROUTE_KEY, routeId, routeJson)
                    .doOnSuccess {
                        logger.info("Route saved: {}", routeId)
                    }
                    .then()
            } catch (e: Exception) {
                logger.error("Failed to save route", e)
                Mono.error(e)
            }
        }
    }

    override fun delete(routeId: Mono<String>): Mono<Void> {
        return routeId.flatMap { id ->
            redisTemplate.opsForHash<String, String>().remove(ROUTE_KEY, id)
                .flatMap { count ->
                    if (count > 0) {
                        logger.info("Route deleted: {}", id)
                        Mono.empty()
                    } else {
                        Mono.error(IllegalArgumentException("Route not found: $id"))
                    }
                }
        }
    }
}