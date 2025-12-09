package cn.icofun.gateway.core

import reactor.core.publisher.Mono

class PluginChain(
    private val plugins: List<GatewayPlugin>,
    private val index: Int = 0
) {

    fun execute(context: GatewayContext): Mono<Void> {
        if (index >= plugins.size) {
            return Mono.empty()
        }

        val plugin = plugins[index]
        val nextChain = PluginChain(plugins, index + 1)

        if (plugin.shouldSkip(context)) {
            return nextChain.execute(context)
        }

        return plugin.execute(context, nextChain)
    }

}