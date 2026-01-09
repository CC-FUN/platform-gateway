package cn.icofun.gateway.route

import cn.icofun.gateway.exception.BusinessException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.route.RouteDefinition
import org.springframework.cloud.gateway.route.RouteDefinitionRepository
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class RedisRouteDefinitionRepository(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper
) : RouteDefinitionRepository {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val ROUTE_KEY = "gateway:routes"

    private val localRouteCache: Cache<String,List<RouteDefinition>> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(60)) // 兜底过期时间
        .maximumSize(1000)
        .build()

    override fun getRouteDefinitions(): Flux<RouteDefinition> {
        val cacheRoutes = localRouteCache.getIfPresent("ALL_ROUTES")
        if (cacheRoutes != null) {
            return Flux.fromIterable(cacheRoutes)
        }
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
            .collectList()
            .doOnNext { routes ->
                logger.debug("🔄 加载路由到本地 L1 缓存，共 {} 条", routes.size)
                localRouteCache.put("ALL_ROUTES", routes)
            }
            .flatMapMany { Flux.fromIterable(it) }
    }

    override fun save(route: Mono<RouteDefinition>): Mono<Void> {
        return route.flatMap { routeDefinition ->
            try {
                val routeId = routeDefinition.id
                val routeJson = objectMapper.writeValueAsString(routeDefinition)

                redisTemplate.opsForHash<String, String>().put(ROUTE_KEY, routeId!!, routeJson)
                    .doOnSuccess {
                        logger.info("Route saved to Redis: {}", routeId)
                    }
                    .then()
            } catch (e: Exception) {
                logger.error("Failed to save route", e)
                Mono.error(BusinessException(500, "route.save.failed"))
            }
        }
    }

    override fun delete(routeId: Mono<String>): Mono<Void> {
        return routeId.flatMap { id ->
            redisTemplate.opsForHash<String, String>().remove(ROUTE_KEY, id)
                .flatMap { count ->
                    if (count > 0) {
                        logger.info("Route deleted from Redis: {}", id)
                        Mono.empty()
                    } else {
                        Mono.error(BusinessException(404, "route.not.found", args = arrayOf(id)))
                    }
                }
        }
    }

    /**
     * [供订阅者调用] 清除本地缓存
     */
    fun clearLocalCache() {
        logger.info("🧹 [L1 Cache] 清除本地路由缓存")
        localRouteCache.invalidateAll()
    }
}