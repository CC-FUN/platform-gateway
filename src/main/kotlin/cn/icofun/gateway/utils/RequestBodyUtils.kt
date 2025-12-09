package cn.icofun.gateway.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

object RequestBodyUtils {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    fun readJsonBody(
        exchange: ServerWebExchange,
        objectMapper: ObjectMapper
    ): Mono<JsonNode> {
        val contentType = exchange.request.headers.contentType

        if (contentType == null || !contentType.includes(MediaType.APPLICATION_JSON)) {
            return Mono.empty()
        }

        return DataBufferUtils.join(exchange.request.body)
            .flatMap { dataBuffer ->
                try {
                    val inputStream = dataBuffer.asInputStream()
                    val jsonNode = objectMapper.readTree(inputStream)
                    Mono.justOrEmpty(jsonNode)
                } catch (e: Exception) {
                    logger.warn("JSON解析失败: ${e.message}")
                    Mono.empty()
                } finally {
                    DataBufferUtils.release(dataBuffer)
                }
            }
    }

    /**
     * 从请求中提取指定的参数（URL参数优先，然后是JSON Body）
     */
    fun extractParams(
        exchange: ServerWebExchange,
        objectMapper: ObjectMapper,
        paramNames: List<String>
    ): Mono<Map<String, String>> {
        val params = mutableMapOf<String, String>()

        val queryParams = exchange.request.queryParams
        // 1. 从 URL 参数提取
        paramNames.forEach { name ->
            val value = queryParams.getFirst(name)
            if (!value.isNullOrBlank()) {
                params[name] = value
            }
        }

        return readJsonBody(exchange, objectMapper)
            .map { json ->
                paramNames.forEach { key ->
                    if (json.has(key) && !params.containsKey(key)) {
                        params[key] = json.get(key).asText()
                    }
                }
                params.toMap()
            }
            .defaultIfEmpty(params)
    }

    /**
     * 验证必需参数
     */
    fun validateRequiredParams(params: Map<String, String>, requiredParams: List<String>) {
        val missingParams = requiredParams.filter { param ->
            params[param].isNullOrBlank()
        }

        if (missingParams.isNotEmpty()) {
            throw IllegalArgumentException(
                "Missing required parameters: ${missingParams.joinToString(", ")}"
            )
        }
    }
}