package cn.icofun.gateway.plugin

import cn.icofun.gateway.core.GatewayContext
import cn.icofun.gateway.core.GatewayPlugin
import cn.icofun.gateway.core.PluginChain
import java.util.concurrent.ThreadLocalRandom
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

@Component
class GrayReleasePlugin : GatewayPlugin {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val GRAY_HEADER = "Gray-Version"

    override fun getName(): String = "GrayRelease"
    override fun getOrder(): Int = 0

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val exchange = context.exchange
        val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
        val metadata = route?.metadata ?: emptyMap()

        val enabled = when (val value = metadata["gray_enabled"]) {
            is Boolean -> value
            is String -> value.toBoolean()
            else -> false
        }
        if (!enabled) {
            return chain.execute(context)
        }

        val weight = when (val value = metadata["gray_weight"]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }
        val version = when (val value = metadata["gray_version"]) {
            is String -> value
            else -> null
        }

        if (version.isNullOrBlank() || weight <= 0) {
            return chain.execute(context)
        }

        val random = ThreadLocalRandom.current().nextInt(100)
        if (random < weight) {
            log.debug("🎯 Route [${route?.id}] hit gray rule: weight=$weight%, version=$version")
            val newRequest = exchange.request.mutate()
                .header(GRAY_HEADER, version)
                .build()
            val newExchange = exchange.mutate().request(newRequest).build()
            context.exchange = newExchange
        }
        return chain.execute(context)
    }
}