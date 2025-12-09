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

    companion object {
        private val SUPPORTED_MODULES = arrayOf("common")
    }

    /**
     * 从ServerHttpRequest获取国际化消息
     */
    fun getMessage(
        key: String,
        request: ServerHttpRequest,
        defaultMessage: String = "",
        args: Array<out Any>? = null
    ): String {
        return try {
            val locale = LocaleUtils.getValidLocaleFromServerRequest(request, SUPPORTED_MODULES)
            messageSource.getMessage(key, args, defaultMessage, locale) ?: defaultMessage
        } catch (ex: Exception) {
            logger.warn("获取国际化消息失败 key: {}, 使用默认消息: {}", key, defaultMessage, ex)
            defaultMessage
        }
    }

    /**
     * 解析带参数的detail字符串并获取国际化消息
     * 格式: "detailKey|arg1|arg2|..."
     */
    fun getDetailMessage(
        detailStr: String,
        request: ServerHttpRequest
    ): String {
        if (detailStr.isBlank()) return ""

        // 1. 直接分割，保留空字符串，确保参数索引对齐
        val detailParts = detailStr.split("|")

        // 2. 获取 Key 并去除首尾空格，防止 " key|arg" 导致找不到 Key
        val detailKey = detailParts[0].trim()

        // 3. 提取参数数组 (如果只有 key 没有参数，args 为空数组)
        val detailArgs = if (detailParts.size > 1) {
            detailParts.drop(1).toTypedArray()
        } else {
            emptyArray()
        }

        // 4. 将 detailKey 同时也作为默认消息，如果找不到国际化配置则直接返回 key
        return getMessage(detailKey, request, detailKey, detailArgs)
    }

}