package cn.icofun.gateway.security

import cn.icofun.gateway.service.GatewaySecurityRuleService
import org.slf4j.LoggerFactory
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.ReactiveAuthorizationManager
import org.springframework.security.core.Authentication
import org.springframework.security.web.server.authorization.AuthorizationContext
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import reactor.core.publisher.Mono
import java.util.*

@Component
class DynamicAuthorizationManager(
    private val securityRuleService: GatewaySecurityRuleService
) : ReactiveAuthorizationManager<AuthorizationContext> {

    private val antPathMatcher = AntPathMatcher()
    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private val SYSTEM_WHITE_LIST = arrayOf(
            "/favicon.ico",
            "/webjars/**",
            "/sys/login",
            "/sys/refresh",
            "/actuator/health"
        )
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun check(
        authentication: Mono<Authentication>,
        context: AuthorizationContext
    ): Mono<AuthorizationDecision> {
        val exchange = context.exchange
        val requestPath = exchange.request.path.value()
        val requestMethod = exchange.request.method.name()

        for (pattern in SYSTEM_WHITE_LIST) {
            if (antPathMatcher.match(pattern, requestPath)) {
                return Mono.just(AuthorizationDecision(true))
            }
        }

        return securityRuleService.getRules()
            .map { rules ->
                // 2. 寻找匹配的规则 (rules 已经按 priority 降序排列)
                Optional.ofNullable(rules.firstOrNull { rule ->
                    val pathMatches = antPathMatcher.match(rule.path, requestPath)
                    val methodMatches = rule.method.isNullOrBlank() ||
                            rule.method.equals(requestMethod, ignoreCase = true)
                    pathMatches && methodMatches
                })
            }
            .flatMap { optionalRule ->
                if (optionalRule.isEmpty) {
                    // 没有匹配到规则：根据业务决定默认行为（此处改为拒绝，增强安全性）
                    Mono.just(AuthorizationDecision(false))
                } else {
                    val matchedRule = optionalRule.get()
                    if (logger.isTraceEnabled) {
                        logger.trace("Match Rule: Path=[${matchedRule.path}], Type=[${matchedRule.type}]")
                    }

                    when (matchedRule.type) {
                        "PERMIT_ALL" -> Mono.just(AuthorizationDecision(true))
                        "AUTHENTICATED" -> {
                            authentication
                                .map { auth -> AuthorizationDecision(auth.isAuthenticated) }
                                .defaultIfEmpty(AuthorizationDecision(false))
                        }

                        else -> Mono.just(AuthorizationDecision(false))
                    }
                }
            }
            .defaultIfEmpty(AuthorizationDecision(false))
    }
}