package cn.icofun.gateway.runtime.protocol.support

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalListener
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class GrpcChannelManager {
    private val logger = LoggerFactory.getLogger(this::class.java)

    // L1 缓存：复用 gRPC 连接 (Host:Port -> Channel)
    private val channelCache = Caffeine.newBuilder()
        .expireAfterAccess(1, TimeUnit.HOURS) // 1小时无访问自动断开
        .maximumSize(100)
        .removalListener(RemovalListener<String, ManagedChannel> { key, channel, cause ->
            logger.info("🔌 关闭 gRPC 通道: $key, 原因: $cause")
            try {
                channel?.shutdown()
            } catch (e: Exception) {
                logger.error("关闭通道失败", e)
            }
        })
        .build<String, ManagedChannel>()

    fun getChannel(host: String, port: Int): ManagedChannel {
        val key = "$host:$port"
        return channelCache.get(key) {
            logger.info("🔌 创建新的 gRPC 通道: $key")
            ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext() // 生产环境请根据安全策略配置 .useTransportSecurity()
                .build()
        }
    }

    @PreDestroy
    fun destroy() {
        channelCache.asMap().values.forEach { it.shutdownNow() }
        channelCache.invalidateAll()
    }
}