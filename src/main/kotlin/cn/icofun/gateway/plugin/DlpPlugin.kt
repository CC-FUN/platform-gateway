package cn.icofun.gateway.plugin

import cn.icofun.gateway.core.GatewayContext
import cn.icofun.gateway.core.GatewayPlugin
import cn.icofun.gateway.core.PluginChain
import cn.icofun.gateway.service.DlpRuleService
import cn.icofun.gateway.utils.StreamingJsonTransformer
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Component
class DlpPlugin(
    private val dlpRuleService: DlpRuleService
) : GatewayPlugin {

    override fun getName() = "DLP-DeepDefense"

    // 必须在 DataMaskingPlugin 之前执行，作为最后一道防线 (Order 越小越先执行，但 Response 装饰器是洋葱模型，
    // 这里我们希望它处理完 DataMasking 漏掉的内容，或者作为独立防线)
    // 建议设置为 -1，紧贴签名插件
    override fun getOrder() = -1

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        return dlpRuleService.getActiveRules().flatMap { rules ->
            if (rules.isEmpty()) {
                return@flatMap chain.execute(context)
            }

            val exchange = context.exchange
            val originalResponse = exchange.response
            val bufferFactory = originalResponse.bufferFactory()

            val decoratedResponse = object : ServerHttpResponseDecorator(originalResponse) {
                override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
                    val contentType = delegate.headers.contentType
                    if (contentType == null || !contentType.includes(MediaType.APPLICATION_JSON)) {
                        return super.writeWith(body)
                    }

                    val transformedBody = Flux.from(body).map { dataBuffer ->
                        val content = ByteArray(dataBuffer.readableByteCount())
                        dataBuffer.read(content)
                        DataBufferUtils.release(dataBuffer)

                        try {
                            // 使用流式转换器，检查每一个 Value
                            val processedBytes = StreamingJsonTransformer.transform(content) { _, value ->
                                var safeValue = value
                                // 遍历所有 DLP 规则进行匹配
                                for (rule in rules) {
                                    val regex = dlpRuleService.getRegex(rule)
                                    if (regex.containsMatchIn(safeValue)) {
                                        // 发现敏感数据，执行替换
                                        safeValue = regex.replace(safeValue, rule.maskChar.repeat(5))
                                    }
                                }
                                safeValue
                            }
                            bufferFactory.wrap(processedBytes)
                        } catch (_: Exception) {
                            // 降级：如果不是标准 JSON，原样返回
                            bufferFactory.wrap(content)
                        }
                    }

                    delegate.headers.remove("Content-Length")
                    return super.writeWith(transformedBody)
                }
            }

            context.exchange = exchange.mutate().response(decoratedResponse).build()
            chain.execute(context)
        }
    }
}