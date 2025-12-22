package cn.icofun.gateway.config

import cn.icofun.gateway.i18n.DbMessageSource
import cn.icofun.gateway.route.RedisRouteDefinitionRepository
import cn.icofun.gateway.service.GatewayConfigService
import cn.icofun.gateway.service.GatewaySecurityRuleService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import reactor.core.publisher.Mono

@Configuration
class RedisConfig {

    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        const val CACHE_REFRESH_TOPIC = "gateway:cache:refresh"
    }

    @Bean
    @Primary
    fun reactiveRedisTemplate(factory: ReactiveRedisConnectionFactory): ReactiveRedisTemplate<String, Any> {
        val stringSerializer = StringRedisSerializer()
        val jacksonSerializer = RedisSerializer.json()

        val context = RedisSerializationContext
            .newSerializationContext<String, Any>(stringSerializer)
            .value(jacksonSerializer)
            .hashKey(stringSerializer)
            .hashValue(jacksonSerializer)
            .build()

        return ReactiveRedisTemplate(factory, context)
    }

    @Bean
    fun cacheRefreshTopic(): ChannelTopic = ChannelTopic(CACHE_REFRESH_TOPIC)

    @Bean
    fun reactiveRedisMessageListenerContainer(
        factory: ReactiveRedisConnectionFactory,
        dbMessageSource: DbMessageSource,
        securityRuleService: GatewaySecurityRuleService,
        redisRouteDefinitionRepository: RedisRouteDefinitionRepository, // 注入路由仓储
        gatewayConfigService: GatewayConfigService,
        topic: ChannelTopic
    ): ReactiveRedisMessageListenerContainer {
        val container = ReactiveRedisMessageListenerContainer(factory)

        container.receive(topic)
            .flatMap { message ->
                val signal = message.message
                logger.info("📨 收到分布式缓存信号: {}", signal)

                when {
                    signal.contains("SECURITY_CHANGE") || signal.contains("security-rule") -> {
                        logger.info("⚡ 触发安全规则 L1 缓存失效")
                        securityRuleService.clearLocalCache()
                        Mono.empty()
                    }

                    signal.contains("gateway:route:refresh") || signal.contains("ROUTE_CHANGE") -> {
                        logger.info("⚡ 触发路由 L1 缓存失效")
                        redisRouteDefinitionRepository.clearLocalCache()
                        Mono.empty()
                    }

                    signal.startsWith("CONFIG:") -> {
                        logger.info("⚡ 触发基础配置 L1 缓存失效: $signal")
                        gatewayConfigService.clearLocalCache(signal)
                        Mono.empty()
                    }

                    signal.contains("i18n") -> {
                        logger.info("⚡ 触发国际化内存重载")
                        dbMessageSource.reloadMono().then()
                    }

                    else -> {
                        logger.warn("⚠️ 收到未知信号 [$signal]，执行全量刷新兜底")
                        Mono.zip(
                            dbMessageSource.reloadMono(),
                            Mono.fromRunnable<Void> { securityRuleService.clearLocalCache() },
                            Mono.fromRunnable<Void> { redisRouteDefinitionRepository.clearLocalCache() }
                        ).then()
                    }
                }

            }
            .subscribe()

        return container
    }
}