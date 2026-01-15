package cn.icofun.gateway.core.engine

import cn.icofun.gateway.core.context.GatewayContext
import cn.icofun.gateway.core.spi.GatewayPlugin
import io.netty.handler.timeout.TimeoutException
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.time.Duration

class PluginChain(
    private val plugins: List<GatewayPlugin>,
    private val index: Int = 0
) {

    private val log = LoggerFactory.getLogger(this::class.java)

    private val defaultTimeout = Duration.ofSeconds(5)

    fun execute(context: GatewayContext): Mono<Void> {
        if (index >= plugins.size) {
            return Mono.empty()
        }

        val plugin = plugins[index]
        val nextChain = PluginChain(plugins, index + 1)

        try {
            if (plugin.shouldSkip(context)) {
                return nextChain.execute(context)
            }
        } catch (e: Exception) {
            log.error("Plugin [${plugin.javaClass.simpleName}] check skip failed", e)
            return nextChain.execute(context)
        }

        return plugin.execute(context, nextChain)
            .timeout(defaultTimeout)
            .onErrorResume { error ->
                val pluginName = plugin.javaClass.simpleName

                if (error is TimeoutException) {
                    log.warn("Gateway Plugin [$pluginName] execution timed out (> ${defaultTimeout.seconds}s). Skipping to next...")
                } else {
                    // 对于预期外的异常，记录 Error 日志
                    log.error("Gateway Plugin [$pluginName] execution failed unexpectedly. Skipping to next...", error)
                }

                if (plugin.isCritical()) {
                    log.error("CRITICAL Plugin [$pluginName] failed. Aborting request to protect backend.")
                    Mono.error(error)
                } else {
                    log.warn("Non-critical Plugin [$pluginName] failed. Skipping to next plugin to keep request alive.")
                    nextChain.execute(context)
                }
            }
    }
}