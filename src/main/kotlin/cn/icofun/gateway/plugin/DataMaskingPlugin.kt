package cn.icofun.gateway.plugin

import cn.icofun.gateway.service.GatewayConfigService
import cn.icofun.gateway.utils.StreamingJsonTransformer
import org.reactivestreams.Publisher
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets

@Component
class DataMaskingPlugin(
    private val gatewayConfigService: GatewayConfigService,
) : GlobalFilter, Ordered {
    private val defaultMaskFields = setOf("phone", "mobile", "idCard", "password", "email", "bankCard")

    companion object {
        const val MASKED_RESPONSE_ATTR = "MASKED_RESPONSE_BODY"
    }

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        return gatewayConfigService.getMaskFieldNamesFromCache()
            .defaultIfEmpty(emptyList())
            .flatMap { configuredFields ->
                val maskFields = if (configuredFields.isEmpty()) defaultMaskFields else configuredFields.toSet()

                val decoratedResponse = object : ServerHttpResponseDecorator(exchange.response) {
                    override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
                        val contentType = delegate.headers.contentType

                        if (contentType == null || !contentType.includes(MediaType.APPLICATION_JSON)) {
                            return super.writeWith(body)
                        }

                        val transformedBody = Flux.from(body).map { dataBuffer ->
                            val content = ByteArray(dataBuffer.readableByteCount())
                            dataBuffer.read(content)
                            DataBufferUtils.release(dataBuffer)

                            val maskedBytes = StreamingJsonTransformer.transform(content) { fieldName, value ->
                                if (maskFields.contains(fieldName)) maskValue(value) else value
                            }

                            exchange.attributes[MASKED_RESPONSE_ATTR] = String(maskedBytes, StandardCharsets.UTF_8)

                            delegate.bufferFactory().wrap(maskedBytes)
                        }

                        delegate.headers.remove("Content-Length")
                        return super.writeWith(transformedBody)
                    }
                }
                chain.filter(exchange.mutate().response(decoratedResponse).build())
            }
    }

    override fun getOrder(): Int = -2

    private fun maskValue(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return when {
            value.length == 11 -> value.replaceRange(3, 7, "*****")
            value.length >= 15 -> value.replaceRange(6, value.length - 4, "********")
            value.contains("@") -> value.replace(Regex("(?<=.).(?=[^@]*?.@)"), "*")
            else -> "******"
        }
    }
}