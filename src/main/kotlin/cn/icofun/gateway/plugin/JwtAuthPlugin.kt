package cn.icofun.gateway.plugin

import cn.icofun.gateway.core.GatewayContext
import cn.icofun.gateway.core.GatewayPlugin
import cn.icofun.gateway.core.PluginChain
import cn.icofun.gateway.exception.BusinessException
import cn.icofun.gateway.plugin.SignaturePlugin.Companion.AUTH_SUCCESS_KEY
import cn.icofun.gateway.service.GatewayConfigService
import cn.icofun.gateway.utils.JwtUtils
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import reactor.core.publisher.Mono

@Component
class JwtAuthPlugin(
    private val jwtUtils: JwtUtils,
    private val configService: GatewayConfigService,
) : GatewayPlugin {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val pathMatcher = AntPathMatcher()

    private val defaultWhiteList = listOf(
        "/actuator/**",
        "/api/public/**",
        "/**/v3/api-docs/**",
        "/swagger-ui/**",
        "/webjars/**",
        "/doc.html",
        "/favicon.ico",
        "/**"
    )

    override fun getName(): String = "JwtAuthPlugin"
    override fun getOrder(): Int = 10
    override fun shouldSkip(context: GatewayContext): Boolean = false

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val exchange = context.exchange
        val request = exchange.request
        val path = request.path.value()

        if (context.getAttribute<Boolean>(AUTH_SUCCESS_KEY) == true) {
            return chain.execute(context)
        }

        return configService.getJwtWhiteList().flatMap { redisList ->
            val finalWhiteList = defaultWhiteList + redisList

            val isWhite = finalWhiteList.any { pathMatcher.match(it, path) }

            if (isWhite) {
                return@flatMap chain.execute(context)
            }

            val authHeader = request.headers.getFirst(HttpHeaders.AUTHORIZATION)

            if (authHeader.isNullOrBlank() || !authHeader.startsWith("Bearer ")) {
                return@flatMap Mono.error(
                    BusinessException(
                        code = 40101,
                        message = "未经授权的访问",
                        httpStatus = HttpStatus.UNAUTHORIZED.value(),
                        detail = "Missing or invalid authorization header"
                    )
                )
            }

            val token = authHeader.substring(7)

            Mono.fromCallable {
                try {
                    val claims = jwtUtils.parseToken(token)

                    val userId = claims.subject
                    val username = claims["username"]?.toString()

                    context.setAttribute("userId", userId)
                    if (!username.isNullOrEmpty()) {
                        context.setAttribute("username", username)
                    }

                    exchange.request.mutate()
                        .header("X-User-Id", userId)
                        .build()

                    logger.debug("User authenticated: $userId")
                } catch (e: Exception) {
                    throw BusinessException(
                        code = 40102,
                        message = "Token 无效或已过期",
                        httpStatus = HttpStatus.UNAUTHORIZED.value(),
                        detail = e.message
                    )
                }
            }.flatMap {
                chain.execute(context)
            }
        }
    }

}