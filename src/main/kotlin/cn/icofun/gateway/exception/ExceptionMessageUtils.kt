package cn.icofun.gateway.exception

object ExceptionMessageUtils {

    /**
     * 从NumberFormatException的message中提取非法值
     * 例如: "For input string: \"aa\"" → "aa"
     */
    fun parseIllegalValueFromNumberException(message: String?): String {
        if (message == null) return "unknown"

        val regex = Regex("""For input string: "([^"]+)"""")
        return regex.find(message)?.groupValues?.get(1) ?: "unknown"
    }

    /**
     * 解析Jackson的错误消息，提取用户友好的部分
     */
    fun parseJacksonErrorMessage(originalMessage: String?): String {
        if (originalMessage == null) return "请求体格式错误"

        return when {
            // 处理缺失必需字段的错误
            originalMessage.contains("missing") &&
                    originalMessage.contains("NULL") &&
                    originalMessage.contains("non-nullable") -> {
                val fieldName = extractFieldName(originalMessage)
                "缺少必需字段: $fieldName"
            }
            // 处理类型不匹配的错误
            originalMessage.contains("Cannot deserialize value") -> {
                "字段类型不匹配"
            }
            // 其他Jackson错误
            else -> "JSON格式错误"
        }
    }

    /**
     * 从错误消息中提取字段名
     */
    private fun extractFieldName(errorMessage: String): String {
        val patterns = listOf(
            "JSON property \"(\\w+)\"".toRegex(),
            "JSON property (\\w+)".toRegex()
        )

        for (pattern in patterns) {
            val match = pattern.find(errorMessage)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return "未知字段"
    }

    /**
     * 解析ClassCastException的错误信息，提取有用的部分
     */
    fun parseClassCastError(
        errorMessage: String,
        errorTypeCast: String,
        errorTypeCastStandardApi: String,
        errorTypeCastGeneric: String,
        errorTypeCastUnknown: String
    ): Triple<String, String, Array<String>?> {
        return when {
            errorMessage.contains("StandardApiResponse cannot be cast to class java.lang.String") -> {
                Triple(
                    errorTypeCast,
                    errorTypeCastStandardApi,
                    null
                )
            }

            errorMessage.contains("cannot be cast") -> {
                val pattern = "class (\\S+) cannot be cast to class (\\S+)".toRegex()
                val match = pattern.find(errorMessage)
                if (match != null) {
                    val fromType = match.groupValues[1].substringAfterLast(".")
                    val toType = match.groupValues[2].substringAfterLast(".")

                    Triple(
                        errorTypeCast,
                        errorTypeCastGeneric,
                        arrayOf(fromType, toType)
                    )
                } else {
                    Triple(
                        errorTypeCast,
                        errorTypeCastUnknown,
                        null
                    )
                }
            }

            else ->
                Triple(
                    errorTypeCast,
                    errorTypeCastUnknown,
                    null
                )
        }
    }

    /**
     * 构建带参数的detail字符串
     * 格式: "detailKey|arg1|arg2|..."
     */
    fun buildDetailWithArgs(detailKey: String, vararg args: Any?): String {
        return if (args.isEmpty()) {
            detailKey
        } else {
            "$detailKey|${args.joinToString("|")}"
        }
    }

    /**
     * 从异常消息中提取关键信息
     */
    fun extractKeyErrorMessage(fullMessage: String): String {
        return when {
            // 处理参数类型错误
            fullMessage.contains("primitive type") -> {
                val paramName = Regex("parameter '(\\w+)'").find(fullMessage)?.groupValues?.get(1) ?: "unknown"
                "参数 '$paramName' 类型不匹配，请使用包装类型代替基本类型"
            }

            // 处理重复提交
            fullMessage.contains("duplicate") || fullMessage.contains("already exists") -> {
                "操作已提交，请勿重复操作"
            }

            // 处理状态冲突
            fullMessage.contains("state") || fullMessage.contains("status") -> {
                "对象状态不符合操作要求"
            }

            // 默认情况：截取前50个字符
            fullMessage.length > 50 -> fullMessage.take(50) + "..."

            else -> fullMessage
        }
    }

    /**
     * 判断是否为生产环境
     */
    fun isProduction(): Boolean {
        return System.getenv("SPRING_PROFILES_ACTIVE") == "prod"
    }

}