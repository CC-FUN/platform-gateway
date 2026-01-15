// cn/icofun/gateway/utils/StreamingJsonTransformer.kt
package cn.icofun.gateway.runtime.transform.support

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import java.io.ByteArrayOutputStream

object StreamingJsonTransformer {
    private val jsonFactory = JsonFactory()

    /**
     * 通用流式转换方法
     * @param input 原始字节数组
     * @param transform 转换逻辑：输入字段名和原始值，输出转换后的值
     */
    fun transform(input: ByteArray, transform: (fieldName: String, value: String) -> String): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val generator = jsonFactory.createGenerator(outputStream)
        val parser = jsonFactory.createParser(input)

        try {
            while (parser.nextToken() != null) {
                // 拷贝当前 Token (比如 START_OBJECT, START_ARRAY 等)
                generator.copyCurrentEvent(parser)

                // 如果碰到字段名
                if (parser.currentToken == JsonToken.FIELD_NAME) {
                    val fieldName = parser.currentName()
                    parser.nextToken() // 移动到值 Token

                    if (parser.currentToken == JsonToken.VALUE_STRING) {
                        // 执行外部传入的转换逻辑
                        val newValue = transform(fieldName, parser.text)
                        generator.writeString(newValue)
                    } else {
                        // 非字符串值原样拷贝
                        generator.copyCurrentEvent(parser)
                    }
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