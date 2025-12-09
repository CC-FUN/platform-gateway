package cn.icofun.gateway.plugin

import cn.icofun.gateway.core.GatewayContext
import cn.icofun.gateway.core.GatewayPlugin
import cn.icofun.gateway.core.PluginChain
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets

@Component
class BodyCachingPlugin : GatewayPlugin {

    private val log = LoggerFactory.getLogger(this::class.java)

    override fun getName() = "BodyCaching"

    // 必须非常早执行，否则后面的人读不到
    override fun getOrder() = -100

    override fun shouldSkip(context: GatewayContext): Boolean {
        val path = context.exchange.request.path.value()
        return path.startsWith("/actuator")
    }

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val method = context.exchange.request.method
        val contentType = context.exchange.request.headers.contentType

        // 只缓存 JSON POST 请求
        if (method.name() == "POST" && contentType?.includes(MediaType.APPLICATION_JSON) == true) {
            log.debug("Caching JSON body for {}", context.exchange.request.path)
            return ServerWebExchangeUtils.cacheRequestBody(context.exchange) { cachedRequest ->
                val mutatedExchange = context.exchange.mutate()
                    .request(cachedRequest)
                    .build()

                val cachedBodyFlux = mutatedExchange.getAttribute<Any>(ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR)
                        as? reactor.core.publisher.Flux<org.springframework.core.io.buffer.DataBuffer>

                if (cachedBodyFlux != null) {
                    DataBufferUtils.join(cachedBodyFlux)
                        .map { dataBuffer ->
                            val bytes = ByteArray(dataBuffer.readableByteCount())
                            dataBuffer.read(bytes)
                            DataBufferUtils.release(dataBuffer)
                            String(bytes, StandardCharsets.UTF_8)
                        }
                        .doOnNext { bodyString ->
                            context.setAttribute("cachedRequestBody", bodyString)
                            log.debug("Cached request body: {}", bodyString)
                        }
                        .then(chain.execute(context.copy(exchange = mutatedExchange)))
                } else {
                    chain.execute(context.copy(exchange = mutatedExchange))
                }

            }
        }

        return chain.execute(context)
    }
}