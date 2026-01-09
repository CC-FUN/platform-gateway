package cn.icofun.gateway.filter

import cn.icofun.gateway.i18n.LocaleUtils
import cn.icofun.gateway.service.GatewayConfigService
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class GlobalAuthFilter(
    private val gatewayConfigService: GatewayConfigService
) : GlobalFilter, Ordered {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val pathMatcher = AntPathMatcher()

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val path = exchange.request.uri.path
        val localeStr = LocaleUtils.getValidLocale(exchange, arrayOf("common")).toString()

        val requestBuilder = exchange.request.mutate()
            .headers { httpHeaders ->
                httpHeaders.remove("X-User-Name")
                httpHeaders.remove("X-User-Id")
                httpHeaders.remove("X-Locale")
            }
        requestBuilder.header("X-Locale", localeStr)

        return gatewayConfigService.getJwtWhitelistFromCache()
            .flatMap<Void> { whitelist ->
                val isWhitelisted = whitelist.any { pattern -> pathMatcher.match(pattern, path) }

                if (isWhitelisted) {
                    if (logger.isDebugEnabled) {
                        logger.debug("🏳️‍🌈 Path [$path] is in JWT whitelist, skipping auth injection.")
                    }
                    // 白名单请求直接放行，不需要查 SecurityContext
                    // 注意：即便在白名单，我们已经注入了 X-Locale
                    return@flatMap chain.filter(exchange.mutate().request(requestBuilder.build()).build())
                }

                // 3. 非白名单，尝试获取登录用户信息并注入 Header
                // (这一步依赖外层 Spring Security 已经完成了鉴权)
                return@flatMap ReactiveSecurityContextHolder.getContext()
                    .handle<Authentication> { ctx, sink ->
                        val auth = ctx.authentication
                        if (auth != null && auth.isAuthenticated) {
                            sink.next(auth)
                        }
                    }
                    .doOnNext { auth ->
                        val username = auth.principal.toString()
                        // 注入用户身份 Header 供下游微服务使用
                        requestBuilder.header("X-User-Id", username)
                        requestBuilder.header("X-User-Name", username)

                        if (logger.isDebugEnabled) {
                            logger.debug("🔑 Authenticated user: $username, headers injected.")
                        }
                    }
                    .then(
                        Mono.defer {
                            chain.filter(exchange.mutate().request(requestBuilder.build()).build())
                        }
                    )
            }
    }


    override fun getOrder(): Int {
        return 0
    }
}