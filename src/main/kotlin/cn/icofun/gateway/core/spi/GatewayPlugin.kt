package cn.icofun.gateway.core.spi

import cn.icofun.gateway.core.context.GatewayContext
import cn.icofun.gateway.core.engine.PluginChain
import reactor.core.publisher.Mono

interface GatewayPlugin {

    fun getName(): String
    fun getOrder(): Int
    fun shouldSkip(context: GatewayContext): Boolean = false
    fun execute(context: GatewayContext, chain: PluginChain): Mono<Void>
    fun isCritical(): Boolean = false
}