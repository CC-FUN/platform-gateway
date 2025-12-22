package cn.icofun.gateway.config

import cn.icofun.gateway.i18n.I18nMessageUtils
import cn.icofun.gateway.model.StandardApiResponse
import cn.icofun.gateway.security.DynamicAuthorizationManager
import cn.icofun.gateway.utils.JwtUtils
import cn.icofun.gateway.utils.MdcUtils
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Configuration
@EnableWebFluxSecurity
class SecurityConfig(
    private val jwtUtils: JwtUtils,
    private val objectMapper: ObjectMapper,
    private val i18nMessageUtils: I18nMessageUtils,
    private val dynamicAuthorizationManager: DynamicAuthorizationManager
) {
    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return BCryptPasswordEncoder()
    }

    @Bean
    fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .addFilterBefore(JwtWebFilter(jwtUtils), SecurityWebFiltersOrder.AUTHENTICATION)
            .authorizeExchange { exchanges ->
                // 1. 跨域预检请求放行
                exchanges.pathMatchers(HttpMethod.OPTIONS).permitAll()

                // 2. 登录与 Token 刷新接口放行
                exchanges.pathMatchers("/favicon.ico", "/webjars/**","/sys/login","/sys/refresh").permitAll()

                exchanges.anyExchange().access(dynamicAuthorizationManager)
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .exceptionHandling { handling ->
                handling.authenticationEntryPoint { exchange, _ ->
                    writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "auth.unauthorized")
                }
                handling.accessDeniedHandler { exchange, _ ->
                    writeErrorResponse(exchange, HttpStatus.FORBIDDEN, "auth.access_denied")
                }
            }
            .build()
    }

    private fun writeErrorResponse(exchange: ServerWebExchange, status: HttpStatus, msgKey: String): Mono<Void> {
        val response = exchange.response
        response.statusCode = status
        response.headers.contentType = MediaType.APPLICATION_JSON

        // 获取多语言消息
        val message = i18nMessageUtils.getMessage(msgKey, null, exchange.request)

        // 构建标准响应体
        val apiResponse = StandardApiResponse.fail<Any>(status.value(), message).apply {
            traceId = exchange.request.headers.getFirst("X-Trace-Id") ?: MdcUtils.getTraceIdOrDefault()
        }

        return try {
            val bytes = objectMapper.writeValueAsBytes(apiResponse)
            val buffer = response.bufferFactory().wrap(bytes)
            response.writeWith(Mono.just(buffer))
        } catch (_: Exception) {
            response.statusCode = HttpStatus.INTERNAL_SERVER_ERROR
            Mono.empty()
        }
    }

    /**
     * 自定义 JWT 过滤器：提取 Header -> 校验 -> 放入 SecurityContext
     */
    class JwtWebFilter(private val jwtUtils: JwtUtils) : WebFilter {
        override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
            val request = exchange.request
            val authHeader = request.headers.getFirst(HttpHeaders.AUTHORIZATION)

            if (!authHeader.isNullOrBlank() && authHeader.startsWith("Bearer ")) {
                val token = authHeader.substring(7).trim()
                try {
                    if (jwtUtils.validateToken(token)) {
                        val username = jwtUtils.getUsername(token)

                        // 构造认证信息 (这里暂时给空权限，如果需要 Role 可以在这里添加)
                        // 比如：val authorities = listOf(SimpleGrantedAuthority("ROLE_ADMIN"))
                        val auth = UsernamePasswordAuthenticationToken(username, null, null)

                        // 将认证信息写入上下文，Security 就能认出你了
                        return chain.filter(exchange)
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                    }
                } catch (_: Exception) {
                    // Token 无效，忽略，继续往下走（Security 会拦截）
                }
            }
            return chain.filter(exchange)
        }
    }
}