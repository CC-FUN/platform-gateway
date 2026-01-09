package cn.icofun.gateway.filter

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

@Component
class GlobalBodyCachingFilter : WebFilter, Ordered {

    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        const val CACHE_REQUEST_BODY_OBJECT_KEY = "cachedRequestBodyString"  // 使用不同的 key 避免冲突
        const val MAX_CACHE_BYTES = 256 * 1024
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = exchange.request
        val headers = request.headers
        val contentType = headers.contentType
        val method = request.method
        val path = exchange.request.uri.path
        val traceId = exchange.request.headers.getFirst("X-Trace-Id") ?: "unknown"

        if (path.startsWith("/internal/monitor")) {
            return chain.filter(exchange)
        }

        logger.debug("[BodyCache] [TraceID: {}] Request: {}, Content-Type: {}", traceId, path, contentType)

        if (method == HttpMethod.GET || method == HttpMethod.DELETE) {
            return chain.filter(exchange)
        }

        if (contentType == null || (!contentType.includes(MediaType.APPLICATION_JSON) && !contentType.includes(MediaType.TEXT_PLAIN))) {
            logger.debug("[BodyCache] [TraceID: $traceId] Skipped due to Content-Type mismatch")
            return chain.filter(exchange)
        }

        return limitAndCacheBody(exchange, chain, traceId)
    }

    private fun limitAndCacheBody(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
        traceId: String
    ): Mono<Void> {
        val bodyCollector = ByteArrayOutputStream()
        var totalRead = 0

        return exchange.request.body
            .takeWhile {
                // ✅ 关键：只要总字节数未超限就继续读取
                totalRead < MAX_CACHE_BYTES
            }
            .doOnNext { dataBuffer ->
                try {
                    // 计算本次可读取的字节数
                    val remaining = MAX_CACHE_BYTES - totalRead
                    val toRead = minOf(dataBuffer.readableByteCount(), remaining)

                    // 读取数据
                    val chunk = ByteArray(toRead)
                    dataBuffer.read(chunk)
                    bodyCollector.write(chunk)
                    totalRead += toRead

                    logger.trace("[BodyCache] [TraceID: $traceId] Read $toRead bytes, total: $totalRead")
                } finally {
                    // ⚠️ 重要：即使不完整消费，也要 release
                    DataBufferUtils.release(dataBuffer)
                }
            }
            .then(Mono.defer {
                val cachedBytes = bodyCollector.toByteArray()
                val bodyString = String(cachedBytes, StandardCharsets.UTF_8)

                logger.debug(
                    "[BodyCache] [TraceID: $traceId] Cached ${cachedBytes.size} bytes, " +
                            "preview: ${bodyString.take(100)}..."
                )

                // 存储 String 版本供插件使用
                exchange.attributes[CACHE_REQUEST_BODY_OBJECT_KEY] = bodyString

                // 创建可重复读的请求装饰器
                val mutatedRequest = object : ServerHttpRequestDecorator(exchange.request) {
                    override fun getBody(): Flux<DataBuffer> {
                        return if (cachedBytes.isEmpty()) {
                            Flux.empty()
                        } else {
                            Flux.defer {
                                val buffer = exchange.response.bufferFactory().wrap(cachedBytes)
                                logger.trace(
                                    "[BodyCache] [TraceID: $traceId] Providing cached body " +
                                            "to downstream, size: ${cachedBytes.size} bytes"
                                )
                                Flux.just(buffer)
                            }
                        }
                    }
                }

                chain.filter(exchange.mutate().request(mutatedRequest).build())
            })
            .doOnError { error ->
                logger.error(
                    "[BodyCache] [TraceID: $traceId] Error processing request body: " +
                            "${error.javaClass.name} - ${error.message}",
                    error
                )
            }
    }
}