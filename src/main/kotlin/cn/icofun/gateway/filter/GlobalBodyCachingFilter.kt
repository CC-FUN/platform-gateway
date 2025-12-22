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
import java.nio.charset.StandardCharsets
import kotlin.math.min

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
        logger.debug("[BodyCache] [TraceID: {}] Request: {}, Content-Type: {}", traceId, path, contentType)

        if (method == HttpMethod.GET || method == HttpMethod.DELETE) {
            return chain.filter(exchange)
        }

        if (contentType == null || (!contentType.includes(MediaType.APPLICATION_JSON) && !contentType.includes(MediaType.TEXT_PLAIN))) {
            logger.debug("[BodyCache] [TraceID: $traceId] Skipped due to Content-Type mismatch")
            return chain.filter(exchange)
        }

        return DataBufferUtils.join(exchange.request.body)
            .flatMap { dataBuffer ->
                val len = dataBuffer.readableByteCount()
                val readLen = min(len, MAX_CACHE_BYTES)
                val traceId = exchange.request.headers.getFirst("X-Trace-Id") ?: "unknown"

                logger.debug("[BodyCache] [TraceID: $traceId] Body size: $len bytes, cached: $readLen bytes")

                val bytes = ByteArray(readLen)
                dataBuffer.read(bytes)

                DataBufferUtils.release(dataBuffer)

                val bodyString = String(bytes, StandardCharsets.UTF_8)
                logger.debug("[BodyCache] [TraceID: $traceId] Cached body preview: ${bodyString.take(100)}...")

                // 只存储 String 版本用于 WAF 等插件检查
                // 不要设置 ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR，避免与 Spring Cloud Gateway 内部机制冲突
                exchange.attributes[CACHE_REQUEST_BODY_OBJECT_KEY] = bodyString

                val mutatedRequest = object : ServerHttpRequestDecorator(exchange.request) {
                    override fun getBody(): Flux<DataBuffer> {
                        return if (bytes.isEmpty()) {
                            Flux.empty()
                        } else {
                            Flux.defer {
                                val buffer = exchange.response.bufferFactory().wrap(bytes)
                                logger.trace("[BodyCache] [TraceID: $traceId] Providing cached body to downstream, size: ${bytes.size} bytes")
                                Flux.just(buffer)
                            }
                        }
                    }
                }
                logger.debug("[BodyCache] [TraceID: $traceId] Request decorated successfully, forwarding to filter chain")
                chain.filter(exchange.mutate().request(mutatedRequest).build())
            }
            .switchIfEmpty(Mono.defer {
                val emptyBodyRequest = object : ServerHttpRequestDecorator(exchange.request) {
                    override fun getBody(): Flux<DataBuffer?> {
                        return Flux.empty()
                    }
                }
                val traceId = exchange.request.headers.getFirst("X-Trace-Id") ?: "unknown"
                logger.debug("[BodyCache] [TraceID: $traceId] Request body is empty")
                chain.filter(exchange.mutate().request(emptyBodyRequest).build())
            })
            .doOnError { error ->
                val traceId = exchange.request.headers.getFirst("X-Trace-Id") ?: "unknown"
                logger.error(
                    "[BodyCache] [TraceID: $traceId] Error processing request body: ${error.javaClass.name} - ${error.message}",
                    error
                )
            }
    }
}