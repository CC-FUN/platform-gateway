package cn.icofun.gateway.exception

import cn.icofun.gateway.i18n.I18nMessageUtils
import org.springframework.http.server.reactive.ServerHttpRequest

object ExceptionMessageUtils {

    /**
     * 获取数字格式化异常的本地化消息
     */
    fun getNumberFormatExceptionMessage(
        message: String?,
        i18n: I18nMessageUtils,
        request: ServerHttpRequest
    ): String {
        if (message == null) return i18n.getMessage("error.number.format", arrayOf("unknown"), request)

        val regex = Regex("""For input string: "([^"]+)"""")
        val value = regex.find(message)?.groupValues?.get(1) ?: "unknown"

        // [重点] 直接在这里调用 getMessage，IDE 就能跟踪到了
        return i18n.getMessage("error.number.format", arrayOf(value), request)
    }

    /**
     * 获取 Jackson 解析异常的本地化消息
     */
    fun getJacksonErrorMessage(
        originalMessage: String?,
        i18n: I18nMessageUtils,
        request: ServerHttpRequest
    ): String {
        if (originalMessage == null) return i18n.getMessage("error.json.format", null, request)

        return when {
            // 缺失必需字段
            originalMessage.contains("missing") &&
                    originalMessage.contains("NULL") &&
                    originalMessage.contains("non-nullable") -> {
                val fieldName = extractFieldName(originalMessage)
                i18n.getMessage("error.json.missing_field", arrayOf(fieldName), request)
            }
            // 类型不匹配
            originalMessage.contains("Cannot deserialize value") -> {
                i18n.getMessage("error.json.type_mismatch", null, request)
            }
            // 其他
            else -> i18n.getMessage("error.json.format", null, request)
        }
    }

    /**
     * 获取类型转换异常消息 (新增)
     */
    fun getClassCastErrorMessage(
        errorMessage: String?,
        i18n: I18nMessageUtils,
        request: ServerHttpRequest
    ): String {
        val msg = errorMessage ?: ""
        val key = when {
            msg.contains("cannot be cast") -> "error.cast.type"
            else -> "error.cast.generic"
        }
        return i18n.getMessage(key, null, request)
    }

    /**
     * 获取通用关键错误消息
     */
    fun getKeyErrorMessage(
        fullMessage: String,
        i18n: I18nMessageUtils,
        request: ServerHttpRequest
    ): String {
        return when {
            // 参数类型错误
            fullMessage.contains("primitive type") -> {
                val paramName = Regex("parameter '(\\w+)'").find(fullMessage)?.groupValues?.get(1) ?: "unknown"
                i18n.getMessage("error.param.type_mismatch", arrayOf(paramName), request)
            }
            // 重复提交
            fullMessage.contains("duplicate") || fullMessage.contains("already exists") -> {
                i18n.getMessage("error.data.duplicate", null, request)
            }
            // 状态冲突
            fullMessage.contains("state") || fullMessage.contains("status") -> {
                i18n.getMessage("error.state.conflict", null, request)
            }
            // 默认
            else -> i18n.getMessage("error.generic", null, request)
        }
    }

    private fun extractFieldName(errorMessage: String): String {
        val patterns = listOf(
            "JSON property \"(\\w+)\"".toRegex(),
            "JSON property (\\w+)".toRegex()
        )
        for (pattern in patterns) {
            val match = pattern.find(errorMessage)
            if (match != null) return match.groupValues[1]
        }
        return "unknown"
    }
}