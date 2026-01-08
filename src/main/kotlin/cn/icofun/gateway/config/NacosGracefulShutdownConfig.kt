package cn.icofun.gateway.config

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.cloud.client.serviceregistry.AbstractAutoServiceRegistration
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.ContextClosedEvent
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import java.lang.reflect.Field

/**
 * Nacos 优雅关闭配置
 * 
 * 用于修复 Nacos 关闭时 NotifyCenter.INSTANCE 为 null 导致的 NPE 问题
 * 
 * 问题原因：
 * - 在应用关闭时，NotifyCenter 可能在 NacosServiceRegistry 之前被销毁
 * - 导致注销服务时访问 NotifyCenter.INSTANCE.sharePublisher 抛出 NPE
 * 
 * 解决方案：
 * - 通过反射禁用 AbstractAutoServiceRegistration 的自动注销功能
 * - 让 Nacos 服务端通过心跳超时自动清理实例
 */
@Configuration
@ConditionalOnClass(name = ["com.alibaba.cloud.nacos.registry.NacosAutoServiceRegistration"])
@Order(Ordered.HIGHEST_PRECEDENCE)
class NacosGracefulShutdownConfig : ApplicationListener<ApplicationReadyEvent> {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        try {
            val context = event.applicationContext
            
            // 查找所有 AbstractAutoServiceRegistration 的 Bean
            val registrations = context.getBeansOfType(AbstractAutoServiceRegistration::class.java)
            
            registrations.values.forEach { registration ->
                try {
                    // 通过反射禁用 running 标志，阻止自动注销
                    val runningField: Field = AbstractAutoServiceRegistration::class.java
                        .getDeclaredField("running")
                    runningField.isAccessible = true
                    
                    // 添加关闭钩子，在关闭时将 running 设为 false
                    context.addApplicationListener { contextEvent ->
                        if (contextEvent is ContextClosedEvent) {
                            try {
                                runningField.set(registration, false)
                                logger.info("已禁用 Nacos 自动服务注销，避免 NotifyCenter NPE")
                            } catch (e: Exception) {
                                logger.debug("禁用 Nacos 自动注销失败（可能已被禁用）: {}", e.message)
                            }
                        }
                    }
                    
                    logger.info("Nacos 优雅关闭配置已生效")
                } catch (e: NoSuchFieldException) {
                    logger.debug("未找到 running 字段，跳过配置")
                } catch (e: Exception) {
                    logger.warn("配置 Nacos 优雅关闭失败: {}", e.message)
                }
            }
        } catch (e: Exception) {
            logger.warn("初始化 Nacos 优雅关闭配置失败: {}", e.message)
        }
    }
}
