package cn.icofun.gateway.core

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
class GatewayPluginRunner(
    pluginList: List<GatewayPlugin>
) : WebFilter, Ordered {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val sortedPlugins = pluginList.sortedBy { it.getOrder() }

    init {
        logger.info("🚀 Gateway Plugin Engine loaded ${sortedPlugins.size} plugins: ${sortedPlugins.map { it.getName() }}")
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
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

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 10
}