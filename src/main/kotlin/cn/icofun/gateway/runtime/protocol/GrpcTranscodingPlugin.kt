package cn.icofun.gateway.runtime.protocol

import cn.icofun.gateway.core.context.GatewayContext
import cn.icofun.gateway.core.engine.PluginChain
import cn.icofun.gateway.core.spi.GatewayPlugin
import cn.icofun.gateway.runtime.protocol.support.GrpcChannelManager
import io.grpc.Channel
import io.grpc.ManagedChannel
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets

/**
 * gRPC 协议转换插件
 * 功能：
 * 1. 识别路由元数据中的 protocol=grpc
 * 2. 拦截 HTTP JSON 请求体
 * 3. 泛化调用后端 gRPC 服务 (Generic Stub)
 * 4. 将 Proto 响应转回 JSON
 */
@Component
class GrpcTranscodingPlugin(
    private val grpcChannelManager: GrpcChannelManager
) : GatewayPlugin {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun getName() = "GrpcTranscoding"

    // 在路由转发之前执行，通常需要替换掉原本的 HTTP 转发逻辑
    // 或者作为 filter 修改请求协议。这里演示作为“终结者”插件直接响应（类似于一种特殊的转发）
    override fun getOrder() = 5000

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val exchange = context.exchange
        val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
        val metadata = route?.metadata ?: emptyMap()

        // 1. 检查是否开启 gRPC 转换
        val protocol = when (val value = metadata["protocol"]) {
            is String -> value
            else -> null
        }
        if (!"grpc".equals(protocol, ignoreCase = true)) {
            return chain.execute(context)
        }

        // 2. 参数解析与校验
        val service = when (val value = metadata["grpc_service"]) {
            is String -> value
            else -> null
        }
        val method = when (val value = metadata["grpc_method"]) {
            is String -> value
            else -> null
        }
        val host = when (val value = metadata["grpc_host"]) {
            is String -> value
            else -> "localhost"
        }
        val port = when (val value = metadata["grpc_port"]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 9090
            else -> 9090
        }

        if (service.isNullOrBlank() || method.isNullOrBlank()) {
            logger.warn("路由 [${route?.id}] 配置了 grpc 协议但缺少 grpc_service 或 grpc_method")
            return chain.execute(context)
        }

        // 3. 获取 HTTP 请求体 (依赖 GlobalBodyCachingFilter)
        // 从 Exchange Attributes 中直接读取已缓存的字符串
        val jsonBody = exchange.attributes["cachedRequestBodyString"] as? String ?: "{}"

        if (logger.isDebugEnabled) {
            logger.debug("发起 gRPC 调用: $host:$port -> $service/$method, Body: $jsonBody")
        }

        // 4. 执行 gRPC 调用 (使用 L1 缓存的 Channel)
        return Mono.fromCallable {
            // 获取复用的连接通道
            val channel = grpcChannelManager.getChannel(host, port)

            // 执行实际调用
            // 注意：此处为泛化调用的模拟点。真实场景需结合 ProtoDescriptor
            genericGrpcCall(channel, service, method, jsonBody)
        }
            .flatMap { responseJson ->
                writeJsonResponse(exchange, responseJson)
            }
            .onErrorResume { e ->
                logger.error("gRPC 调用失败: ${e.message}", e)
                writeJsonResponse(exchange, "{\"code\": 500, \"error\": \"gRPC Error: ${e.message}\"}", 500)
            }
    }

    /**
     * 模拟 gRPC 泛化调用
     * 在真实生产环境中，这里会使用 `DynamicMessage` 和 `Stub` 结合 `.proto` 描述文件进行反射调用
     */
    private fun genericGrpcCall(
        channel: Channel,
        service: String,
        method: String,
        json: String
    ): String {
        // 将 json 转为 Message，再通过 MethodDescriptor 发起调用。

        // 演示代码：直接返回成功响应
        // 实际上 channel 已经被连接池管理，这里证明我们可以拿到 channel 对象
        return """
            {
                "status": "success",
                "data": {
                    "service": "$service",
                    "method": "$method",
                    "channel_state": "${(channel as? ManagedChannel)?.getState(false)}",
                    "echo_request": $json
                }
            }
        """.trimIndent()
    }

    /**
     * 将 gRPC 的响应结果 (JSON 格式) 写回 HTTP 响应
     */
    private fun writeJsonResponse(exchange: ServerWebExchange, json: String, statusCode: Int = 200): Mono<Void> {
        exchange.response.statusCode = HttpStatus.valueOf(statusCode)
        exchange.response.headers.contentType = MediaType.APPLICATION_JSON
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val buffer = exchange.response.bufferFactory().wrap(bytes)
        return exchange.response.writeWith(Mono.just(buffer))
    }
}