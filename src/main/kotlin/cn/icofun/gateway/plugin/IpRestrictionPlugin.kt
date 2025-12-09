package cn.icofun.gateway.plugin

import cn.icofun.gateway.core.GatewayContext
import cn.icofun.gateway.core.GatewayPlugin
import cn.icofun.gateway.core.PluginChain
import cn.icofun.gateway.exception.BusinessException
import cn.icofun.gateway.service.GatewayConfigService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.net.InetSocketAddress

@Component
class IpRestrictionPlugin(
    private val configService: GatewayConfigService
) : GatewayPlugin {
    private val log = LoggerFactory.getLogger(this::class.java)

    companion object {
        private val INTERNAL_WHITELIST = setOf("127.0.0.1", "0:0:0:0:0:0:0:1")
    }

    override fun getName(): String = "IpRestriction"
    override fun getOrder(): Int = -200

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val ip = getClientIp(context.exchange)

        log.debug("Incoming request from IP: {}", ip)

        if (INTERNAL_WHITELIST.contains(ip)) {
            return chain.execute(context)
        }

        return configService.isIpBlacklisted(ip)
            .flatMap { isBlackListed ->
                if (isBlackListed) {
                    log.warn("Blocked request from blacklisted IP: $ip")
                    Mono.error(
                        BusinessException(
                            code = 40301,
                            message = "IP access denied",
                            httpStatus = HttpStatus.FORBIDDEN.value(),
                            detail = "Your IP $ip is in the blacklist"
                        )
                    )
                } else {
                    chain.execute(context)
                }
            }
    }

    private fun getClientIp(exchange: ServerWebExchange): String {
        val headers = exchange.request.headers

        val xForwardedFor = headers.getFirst("X-Forwarded-For")

        if (!xForwardedFor.isNullOrBlank()) {
            return xForwardedFor.split(",")[0].trim()
        }

        val remoteAddress: InetSocketAddress? = exchange.request.remoteAddress
        return remoteAddress?.address?.hostAddress ?: "unknown"
    }
}