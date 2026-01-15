package cn.icofun.gateway.core.engine

import cn.icofun.gateway.core.context.GatewayContext
import cn.icofun.gateway.core.spi.GatewayPlugin
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class GatewayPluginRunner(
    pluginList: List<GatewayPlugin>
) : GlobalFilter, Ordered {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val sortedPlugins = pluginList.sortedBy { it.getOrder() }

    init {
        logger.info("🚀 SaaS Gateway Engine Loaded. Plugins: ${sortedPlugins.joinToString { it.getName() }}")
    }

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val context = GatewayContext(exchange)

        return PluginChain(sortedPlugins).execute(context)
            .then(Mono.defer {
                if (!exchange.response.isCommitted) {
                    chain.filter(exchange)
                } else {
                    Mono.empty()
                }
            })
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 200
}