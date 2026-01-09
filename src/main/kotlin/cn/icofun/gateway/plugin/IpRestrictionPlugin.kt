package cn.icofun.gateway.plugin

import cn.icofun.gateway.core.GatewayContext
import cn.icofun.gateway.core.GatewayPlugin
import cn.icofun.gateway.core.PluginChain
import cn.icofun.gateway.exception.BusinessException
import cn.icofun.gateway.service.GatewayConfigService
import cn.icofun.gateway.utils.IpUtils
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

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
    override fun isCritical() = true

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val ip = IpUtils.getClientIp(context.exchange)

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
                            message = "secure.ip.denied",
                            httpStatus = HttpStatus.FORBIDDEN.value(),
                            detail = "secure.ip.blocked.detail",
                            args = arrayOf(ip)
                        )
                    )
                } else {
                    chain.execute(context)
                }
            }
    }
}