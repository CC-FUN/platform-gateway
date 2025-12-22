package cn.icofun.gateway.service

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class CacheRefreshPublisher(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val cacheRefreshTopic: ChannelTopic
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * 发送缓存刷新信号
     * @param eventType 事件类型标识 (如: ROUTE_CHANGE, SECURITY_CHANGE)
     */
    fun publishRefresh(eventType: String): Mono<Long> {
        logger.info("📡 广播缓存刷新信号: {}", eventType)
        // 直接发送事件标识，不再拼接多余的描述信息，方便消费者解析
        return redisTemplate.convertAndSend(cacheRefreshTopic.topic, eventType)
            .doOnError { error -> logger.error("❌ Redis消息发布失败", error) }
    }
}