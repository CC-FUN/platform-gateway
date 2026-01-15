package cn.icofun.gateway.infra.config

import cn.icofun.gateway.infra.i18n.I18nMessageUtils
import cn.icofun.gateway.infra.model.StandardApiResponse
import cn.icofun.gateway.runtime.security.support.DynamicAuthorizationManager
import cn.icofun.gateway.infra.utils.JwtUtils
import cn.icofun.gateway.infra.utils.MdcUtils
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
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource
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
            .cors { it.configurationSource(corsConfigurationSource()) }
            .csrf { it.disable() }
            .headers { headers ->
                headers.frameOptions { it.disable() }
            }
            .addFilterBefore(JwtWebFilter(jwtUtils), SecurityWebFiltersOrder.AUTHENTICATION)
            .authorizeExchange { exchanges ->
                // 1. 跨域预检请求放行
                exchanges.pathMatchers(HttpMethod.OPTIONS).permitAll()

                // 2. 登录与 Token 刷新接口放行
                exchanges.pathMatchers("/favicon.ico", "/webjars/**", "/sys/login", "/sys/refresh").permitAll()

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
            var token = request.headers.getFirst(HttpHeaders.AUTHORIZATION)?.let {
                if (it.startsWith("Bearer ")) it.substring(7).trim() else null
            }

            if (token.isNullOrBlank()) {
                token = request.cookies.getFirst("Admin-Token")?.value
            }

            if (token.isNullOrBlank()) {
                return chain.filter(exchange)
            }

            return jwtUtils.parseTokenMono(token)
                .flatMap { claims ->
                    val username = claims.subject
                    val auth = UsernamePasswordAuthenticationToken(username, null, listOf())

                    chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                }.onErrorResume { _ ->
                    chain.filter(exchange)
                }
        }
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        // 允许的跨域来源，开发环境可以用 "*" (配合 allowCredentials=true 时需用 allowedOriginPatterns)
        configuration.allowedOriginPatterns = listOf("*")
        // 允许的方法
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
        // 允许的头信息
        configuration.allowedHeaders = listOf("*")
        // 允许携带凭证 (Cookie 等)
        configuration.allowCredentials = true
        // 预检请求缓存时间 (秒)
        configuration.maxAge = 3600L

        val source = UrlBasedCorsConfigurationSource()
        // 对所有路径生效
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}