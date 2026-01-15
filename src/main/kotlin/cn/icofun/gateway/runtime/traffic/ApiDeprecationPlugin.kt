package cn.icofun.gateway.runtime.traffic

import cn.icofun.gateway.core.context.GatewayContext
import cn.icofun.gateway.core.engine.PluginChain
import cn.icofun.gateway.core.spi.GatewayPlugin
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class ApiDeprecationPlugin : GatewayPlugin {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun getName(): String = "ApiDeprecation"
    override fun getOrder(): Int = 0

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val exchange = context.exchange
        val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
        val metadata = route?.metadata ?: emptyMap()

        val isDeprecated = when (val value = metadata["deprecated"]) {
            is Boolean -> value
            is String -> value.toBoolean()
            else -> false
        }
        if (isDeprecated) {
            val response = exchange.response

            response.headers.add("Deprecated", "true")

            val sunsetDate = when (val value = metadata["sunset"]) {
                is String -> value
                else -> null
            }
            if (!sunsetDate.isNullOrBlank()) {
                response.headers.add("Sunset", sunsetDate)
            }

            val link = when (val value = metadata["deprecation_link"]) {
                is String -> value
                else -> null
            }
            if (!link.isNullOrBlank()) {
                response.headers.add("Link", "<$link>; rel=\"deprecation\"")
            }

            logger.debug("⚠️ Route [${route?.id}] is deprecated. Headers injected.")
        }
        return chain.execute(context)
    }
}