package cn.icofun.gateway.runtime.transform.support

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import java.io.ByteArrayOutputStream

/**
 * 流式 JSON 投影工具 (Smart Optimization)
 * 优化点：
 * 1. 自动保留外层信封 (code, msg, success 等)
 * 2. 自动穿透容器字段 (data, result, list 等) 进行内部裁剪
 * 3. 零拷贝、流式处理，无需完全反序列化
 */
object StreamingJsonProjector {
    private val jsonFactory = JsonFactory()

    // 【配置】默认总是保留的字段 (通常是状态码、提示信息、分页元数据)
    private val DEFAULT_ALWAYS_KEEP = setOf(
        "code", "msg", "message", "success", "err_msg",
        "total", "count", "page", "size", "timestamp"
    )

    // 【配置】默认需要“钻取/穿透”的容器字段 (进入这些字段内部查找目标，而不是直接丢弃)
    private val DEFAULT_DRILL_DOWN = setOf(
        "data", "result", "items", "list", "rows", "records"
    )

    /**
     * 执行投影
     * @param input 原始 JSON 字节
     * @param allowedFields 用户请求保留的字段 (如: id, name)
     */
    fun project(input: ByteArray, allowedFields: Set<String>): ByteArray {
        if (allowedFields.isEmpty()) return input

        val outputStream = ByteArrayOutputStream()
        val generator = jsonFactory.createGenerator(outputStream)
        val parser = jsonFactory.createParser(input)

        try {
            // 循环处理所有 Token
            while (parser.nextToken() != null) {
                if (parser.currentToken == JsonToken.FIELD_NAME) {
                    val fieldName = parser.currentName() ?: ""

                    // 1. 用户显式指定的字段 -> 保留 (复制整个子树)
                    if (allowedFields.contains(fieldName)) {
                        generator.writeFieldName(fieldName)
                        parser.nextToken()
                        generator.copyCurrentStructure(parser)
                    }
                    // 2. 系统默认保留的字段 (外层信封) -> 保留 (复制整个子树)
                    else if (DEFAULT_ALWAYS_KEEP.contains(fieldName)) {
                        generator.writeFieldName(fieldName)
                        parser.nextToken()
                        generator.copyCurrentStructure(parser)
                    }
                    // 3. 容器字段 -> 穿透 (写入字段名，然后进入内部处理 children)
                    else if (DEFAULT_DRILL_DOWN.contains(fieldName)) {
                        generator.writeFieldName(fieldName)
                        val nextToken = parser.nextToken()

                        // 如果是对象或数组，只复制开始标记，然后让循环继续处理内部字段
                        if (nextToken == JsonToken.START_OBJECT || nextToken == JsonToken.START_ARRAY) {
                            generator.copyCurrentEvent(parser)
                        } else {
                            // 如果容器字段的值是 null 或基本类型，直接复制
                            generator.copyCurrentStructure(parser)
                        }
                    }
                    // 4. 其他无关字段 -> 丢弃 (跳过整个子树)
                    else {
                        parser.nextToken()
                        parser.skipChildren()
                    }
                } else {
                    // 非字段名 Token (如 START_OBJECT, START_ARRAY, END_OBJECT 等)
                    // 在穿透模式下，我们需要保留结构标记
                    generator.copyCurrentEvent(parser)
                }
            }
            generator.flush()
            return outputStream.toByteArray()
        } finally {
            generator.close()
            parser.close()
        }
    }
}