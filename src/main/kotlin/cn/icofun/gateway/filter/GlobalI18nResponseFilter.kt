package cn.icofun.gateway.filter

import cn.icofun.gateway.i18n.I18nMessageUtils
import cn.icofun.gateway.utils.StreamingJsonTransformer
import org.reactivestreams.Publisher
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Component
class GlobalI18nResponseFilter(
    private val i18nUtils: I18nMessageUtils
) : GlobalFilter, Ordered {

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
        val module = route?.uri?.host ?: "common"

        val decoratedResponse = object : ServerHttpResponseDecorator(exchange.response) {
            override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
                val contentType = exchange.response.headers.contentType

                if (contentType != null && contentType.includes(MediaType.APPLICATION_JSON)) {

                    val transformedBody = Flux.from(body).map { dataBuffer ->
                        val content = ByteArray(dataBuffer.readableByteCount())
                        dataBuffer.read(content)
                        DataBufferUtils.release(dataBuffer)

                        val translatedBytes = StreamingJsonTransformer.transform(content) { fieldName, value ->
                            if (fieldName == "message" || fieldName == "data") {
                                translate(value, exchange, module)
                            } else value
                        }

                        delegate.bufferFactory().wrap(translatedBytes)
                    }

                    delegate.headers.remove("Content-Length")
                    return super.writeWith(transformedBody)
                }
                return super.writeWith(body)
            }
        }
        return chain.filter(exchange.mutate().response(decoratedResponse).build())
    }

    private fun translate(raw: String, exchange: ServerWebExchange, module: String): String {
        if (raw.isBlank()) return raw
        return if (raw.contains("|")) {
            val parts = raw.split("|")
            val key = parts[0]
            val args = parts[1].split(",").toTypedArray()
            i18nUtils.getMessage(key, args, exchange.request, module)
        } else {
            i18nUtils.getMessage(raw, null, exchange.request, module)
        }
    }

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE - 1
}