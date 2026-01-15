package cn.icofun.gateway.admin.route.init

import cn.icofun.gateway.admin.route.repository.GatewayRouteRepository
import cn.icofun.gateway.admin.route.support.RedisRouteDefinitionRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.cloud.gateway.event.RefreshRoutesEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class RouteInitRunner(
    private val gatewayRouteRepository: GatewayRouteRepository,
    private val redisRouteDefinitionRepository: RedisRouteDefinitionRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val gatewayRouteConverter: GatewayRouteConverter
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun run(args: ApplicationArguments) {
        logger.info("Starting to load routes from database to Redis...")

        gatewayRouteRepository.findAll()
            .filter { it.enabled }
            .flatMap { entity ->
                try {
                    val definition = gatewayRouteConverter.convert(entity)
                    redisRouteDefinitionRepository.save(Mono.just(definition))
                        .thenReturn(definition)
                        .doOnNext { def ->
                            logger.info("路由加载成功: ${def.id}")
                        }
                } catch (e: Exception) {
                    logger.error("Failed to convert/load route: ${entity.id}", e)
                    Mono.empty()
                }
            }
            .collectList()
            .doOnError { e -> logger.error("Critical error during route initialization", e) }
            .subscribe { routes ->
                logger.info("✅ 已成功加载 ${routes.size} 条路由到 Redis")

                // 1. 清除 RedisRepository 的本地 Caffeine 缓存 (关键!)
                redisRouteDefinitionRepository.clearLocalCache()

                // 2. 发送路由刷新事件，通知网关重新加载 (关键!)
                eventPublisher.publishEvent(RefreshRoutesEvent(this))
                logger.info("🔄 已发送路由刷新事件，网关路由表更新完毕")
            }
    }

}