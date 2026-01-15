package cn.icofun.gateway.runtime.security

import cn.icofun.gateway.core.context.GatewayContext
import cn.icofun.gateway.core.engine.PluginChain
import cn.icofun.gateway.core.spi.GatewayPlugin
import cn.icofun.gateway.infra.exception.BusinessException
import cn.icofun.gateway.infra.service.GatewayConfigService
import cn.icofun.gateway.infra.utils.Sha256Utils
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.TreeMap
import kotlin.collections.iterator
import kotlin.math.abs

@Component
class SignaturePlugin(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val configService: GatewayConfigService
) : GatewayPlugin {
    private val pathMatcher = AntPathMatcher()

    companion object {
        const val AUTH_SUCCESS_KEY = "GATEWAY_AUTH_SUCCESS"
    }

    private val SIGN_TIMEOUT_MS = 5 * 60 * 1000L
    private val NONCE_KEY_PREFIX = "gateway:nonce:" // Redis Key 前缀

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

    override fun getName(): String = "SignaturePlugin"
    override fun getOrder(): Int = 5
    override fun shouldSkip(context: GatewayContext): Boolean = false
    override fun isCritical() = true

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val exchange = context.exchange
        val request = exchange.request
        val path = request.path.value()

        val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
        val metadata = route?.metadata ?: emptyMap()
        val signEnabled = when (val value = metadata["sign_enabled"]) {
            is Boolean -> value.toString()
            is String -> value
            else -> null
        }

        if (signEnabled == "false") {
            return chain.execute(context)
        }

        return shouldCheck(path, signEnabled).flatMap { needCheck ->
            if (!needCheck) {
                return@flatMap chain.execute(context)
            }

            performSignatureCheck(context, chain)
        }
    }

    private fun shouldCheck(path: String, routeConfig: String?): Mono<Boolean> {
        if (routeConfig == "true") return Mono.just(true)

        return configService.getSignWhitelistFromCache().map { redisLis ->
            val finalWhiteList = defaultWhiteList + redisLis
            val isWhite = finalWhiteList.any { pathMatcher.match(it, path) }
            !isWhite
        }
    }

    private fun performSignatureCheck(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val request = context.exchange.request
        val headers = request.headers

        if (!headers.getFirst(HttpHeaders.AUTHORIZATION).isNullOrBlank()) {
            return chain.execute(context)
        }

        val appInfo = headers.getFirst("X-App-Info")
        val sign = headers.getFirst("X-Sign")
        val timestampStr = headers.getFirst("X-Timestamp")
        val nonce = headers.getFirst("X-Nonce")

        if (appInfo.isNullOrBlank() || sign.isNullOrBlank() || timestampStr.isNullOrBlank() || nonce.isNullOrBlank()) {
            return Mono.error(BusinessException(40001, "sign.param.missing", HttpStatus.BAD_REQUEST.value()))
        }

        val timestamp = try {
            timestampStr.toLong()
        } catch (_: Exception) {
            return Mono.error(BusinessException(40003, "sign.timestamp.invalid", HttpStatus.BAD_REQUEST.value()))
        }

        val now = System.currentTimeMillis()
        if (abs(now - timestamp) > SIGN_TIMEOUT_MS) {
            return Mono.error(BusinessException(40004, "sign.expired", HttpStatus.FORBIDDEN.value()))
        }

        val appInfoParts = appInfo.split(".")
        if (appInfoParts.size < 3) {
            return Mono.error(BusinessException(40002, "sign.app_info.invalid", HttpStatus.FORBIDDEN.value()))
        }
        val appId = appInfoParts.last()

        return configService.getAppSecret(appId)
            .switchIfEmpty(
                Mono.error(BusinessException(40002, "sign.appid.invalid", HttpStatus.FORBIDDEN.value()))
            ).flatMap { appSecret ->
                verifySignature(context, chain, appInfo, sign, timestampStr, nonce, appSecret)
            }
    }

    private fun verifySignature(
        context: GatewayContext, chain: PluginChain,
        appInfo: String, sign: String, timestamp: String, nonce: String, secret: String
    ): Mono<Void> {
        val request = context.exchange.request
        val sortedParams = TreeMap<String, String>()
        request.queryParams.forEach { (k, v) ->
            if (v.isNotEmpty()) {
                sortedParams[k] = v[0]
            }
        }

        val sb = StringBuilder()
        sb.append("app_info=").append(appInfo).append("&")
        sb.append("nonce=").append(nonce).append("&")
        sb.append("timestamp=").append(timestamp).append("&")
        for ((key, value) in sortedParams) {
            sb.append(key).append("=").append(value).append("&")
        }

        sb.append("secret=").append(secret)

        val cachedBody = context.getAttribute<String>("cachedRequestBodyString")
        if (!cachedBody.isNullOrBlank()) {
            sb.append("body=").append(cachedBody).append("&")
        }


        val calculated = Sha256Utils.sha256AsHex(sb.toString().toByteArray())
        if (!calculated.equals(sign, ignoreCase = true)) {
            return Mono.error(BusinessException(40005, "sign.check.failed", HttpStatus.FORBIDDEN.value()))
        }

        val nonceKey = NONCE_KEY_PREFIX + nonce
        return redisTemplate.opsForValue()
            .setIfAbsent(nonceKey, "1", Duration.ofMillis(SIGN_TIMEOUT_MS))
            .flatMap { success ->
                if (success) {
                    context.setAttribute(AUTH_SUCCESS_KEY, true)
                    chain.execute(context)
                } else {
                    Mono.error(
                        BusinessException(
                            40006,
                            "sign.replay.attack",
                            HttpStatus.FORBIDDEN.value()
                        )
                    )
                }
            }
    }
}