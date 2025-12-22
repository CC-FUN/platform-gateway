package cn.icofun.gateway.i18n

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Component

/**
 * 国际化消息工具类
 * 统一管理国际化消息的获取逻辑
 */
@Component
class I18nMessageUtils(
    private val messageSource: MessageSource
) {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    /**
     * 智能翻译逻辑：优先找模块内的 Key，找不到再找 common 模块
     * @param module 服务名 (如 user-service)
     * @param key 词条键 (如 user.not.found)
     */
    fun getMessage(
        key: String,
        args: Array<out Any>? = null,
        request: ServerHttpRequest,
        module: String = "platform-gateway" // 默认归属网关
    ): String {
        return try {
            val locale = LocaleUtils.getValidLocaleFromServerRequest(request, arrayOf("common"))
            if (module == "common") {
                messageSource.getMessage(key, args, key, locale) ?: key
            } else {
                val moduleKey = "$module:$key"
                // 优先找模块特定的翻译
                val result = messageSource.getMessage(moduleKey, args, null, locale)
                // 找不到则找全局定义的该 key (兜底)
                result ?: messageSource.getMessage(key, args, key, locale) ?: key
            }
        }catch (_: Exception){
            logger.debug("I18n key not found: {}", key)
            key
        }
    }
}