package cn.icofun.gateway.runtime.transform

import cn.icofun.gateway.core.context.GatewayContext
import cn.icofun.gateway.core.engine.PluginChain
import cn.icofun.gateway.core.spi.GatewayPlugin
import cn.icofun.gateway.infra.service.GatewayConfigService
import cn.icofun.gateway.runtime.transform.support.StreamingJsonProjector
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Component
class ResponseProjectionPlugin(
    private val gatewayConfigService: GatewayConfigService
) : GatewayPlugin {

    // 定义触发投影的 URL 参数 Key，例如 /api/users?_fields=id,username
    private val PROJECTION_PARAM = "_fields"

    override fun getName(): String = "ResponseProjection"

    // 顺序必须在 DataMaskingPlugin 之前或之后，取决于你是想先脱敏再裁剪，还是先裁剪再脱敏
    // 建议：先裁剪 (Projection) 减少数据量，再脱敏 (Masking)。这里设为 -3 (Masking 是 -2)
    override fun getOrder(): Int = -3

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val exchange = context.exchange
        val request = exchange.request

        // 1. 检查是否存在 _fields 参数
        val fieldsParam = request.queryParams.getFirst(PROJECTION_PARAM)
        if (fieldsParam.isNullOrBlank()) {
            return chain.execute(context)
        }

        return gatewayConfigService.getProjectionFieldsFromCache()
            .defaultIfEmpty(emptyList())
            .flatMap { configuredAllowedFields ->
                val requestedFields = fieldsParam.split(",").map { it.trim() }.toSet()

                val finalAllowedFields = if (configuredAllowedFields.isEmpty()) {
                    requestedFields // 也可以改为返回 chain.execute(context) 禁用未配置的投影
                } else {
                    requestedFields.intersect(configuredAllowedFields.toSet())
                }

                if (finalAllowedFields.isEmpty()) {
                    return@flatMap chain.execute(context)
                }

                val originalResponse = exchange.response
                val bufferFactory = originalResponse.bufferFactory()

                val decoratedResponse = object : ServerHttpResponseDecorator(originalResponse) {
                    override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
                        val contentType = delegate.headers.contentType

                        // 仅处理 JSON 类型
                        if (contentType == null || !contentType.includes(MediaType.APPLICATION_JSON)) {
                            return super.writeWith(body)
                        }

                        val transformedBody = Flux.from(body).map { dataBuffer ->
                            val content = ByteArray(dataBuffer.readableByteCount())
                            dataBuffer.read(content)
                            DataBufferUtils.release(dataBuffer)

                            try {
                                // 使用优化后的 Smart Projector 执行投影
                                val projectedBytes = StreamingJsonProjector.project(content, finalAllowedFields)
                                bufferFactory.wrap(projectedBytes)
                            } catch (_: Exception) {
                                // 降级处理：防止非标准 JSON 导致崩溃
                                bufferFactory.wrap(content)
                            }
                        }

                        // 包体大小已变，必须移除旧的长度头
                        delegate.headers.remove("Content-Length")
                        return super.writeWith(transformedBody)
                    }
                }
                context.exchange = exchange.mutate().response(decoratedResponse).build()
                chain.execute(context)
            }
    }
}