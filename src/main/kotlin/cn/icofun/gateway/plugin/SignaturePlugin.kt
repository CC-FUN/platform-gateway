package cn.icofun.gateway.plugin

import cn.icofun.gateway.core.GatewayContext
import cn.icofun.gateway.core.GatewayPlugin
import cn.icofun.gateway.core.PluginChain
import cn.icofun.gateway.exception.BusinessException
import cn.icofun.gateway.service.GatewayConfigService
import cn.icofun.gateway.utils.Sha256Utils
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.*
import kotlin.math.abs

@Component
class SignaturePlugin(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val configService: GatewayConfigService
) : GatewayPlugin {
    private val logger = LoggerFactory.getLogger(this::class.java)
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

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val request = context.exchange.request
        val path = request.path.value()

        return configService.getSignWhitelist().flatMap { redisList ->
            val finalWhiteList = defaultWhiteList + redisList
            val isWhite = finalWhiteList.any { pathMatcher.match(it, path) }

            if (isWhite) {
                return@flatMap chain.execute(context)
            }

            val headers = request.headers
            val authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION)
            if (!authHeader.isNullOrBlank() && authHeader.startsWith("Bearer ")) {
                return@flatMap chain.execute(context)
            }


            val appInfo = headers.getFirst("X-App-Info")
            val sign = headers.getFirst("X-Sign")
            val timestampStr = headers.getFirst("X-Timestamp")
            val nonce = headers.getFirst("X-Nonce")

            if (appInfo.isNullOrBlank() || sign.isNullOrBlank() || timestampStr.isNullOrBlank() || nonce.isNullOrBlank()) {
                return@flatMap Mono.error(BusinessException(40001, "签名参数缺失", HttpStatus.BAD_REQUEST.value()))
            }

            val timestamp = try {
                timestampStr.toLong()
            } catch (_: Exception) {
                return@flatMap Mono.error(BusinessException(40003, "时间戳格式错误", HttpStatus.BAD_REQUEST.value()))
            }

            val now = System.currentTimeMillis()
            if (abs(now - timestamp) > SIGN_TIMEOUT_MS) {
                return@flatMap Mono.error(BusinessException(40004, "请求已过期", HttpStatus.FORBIDDEN.value()))
            }

            val appInfoParts = appInfo.split(".")
            if (appInfoParts.size <3) {
                return@flatMap Mono.error(BusinessException(40002, "app_info格式错误", HttpStatus.FORBIDDEN.value()))
            }

            val appId = appInfoParts.last() // 提取出真实的 appId 用于查库

            configService.getAppSecret(appId)
                .switchIfEmpty(
                    Mono.error(BusinessException(40002, "非法的 AppId", HttpStatus.FORBIDDEN.value()))
                ).flatMap { appSecret ->
                    val sortedParams = TreeMap<String, String>()
                    request.queryParams.forEach { (k, v) ->
                        if (v.isNotEmpty()) {
                            sortedParams[k] = v[0]
                        }
                    }

                    sortedParams.remove("X-App-Secret")
                    sortedParams.remove("X-Sign")
                    sortedParams.remove("X-Timestamp")
                    sortedParams.remove("X-Nonce")

                    val sb = StringBuilder()
                    sb.append("app_info=").append(appInfo).append("&")
                    sb.append("nonce=").append(nonce).append("&")
                    sb.append("timestamp=").append(timestampStr).append("&")

                    for ((key, value) in sortedParams) {
                        sb.append(key).append("=").append(value).append("&")
                    }

                    val cachedBody = context.getAttribute<String>("cachedRequestBody")
                    if (!cachedBody.isNullOrBlank()) {
                        sb.append("body=").append(cachedBody).append("&")
                    }

                    sb.append("secret=").append(appSecret)

                    val calculatedSign = Sha256Utils.sha256AsHex(sb.toString().toByteArray())

                    if (logger.isDebugEnabled){
                        logger.debug("Signature check: AppInfo=$appInfo, ServerSign=$calculatedSign, ClientSign=$sign")
                    }

                    if (!calculatedSign.equals(sign, ignoreCase = true)) {
                        return@flatMap Mono.error(
                            BusinessException(
                                40005,
                                "签名校验失败",
                                HttpStatus.FORBIDDEN.value()
                            )
                        )
                    }

                    val nonceKey = NONCE_KEY_PREFIX + nonce
                    redisTemplate.opsForValue()
                        .setIfAbsent(nonceKey, "1", Duration.ofMillis(SIGN_TIMEOUT_MS))
                        .flatMap { success ->
                            if (success) {
                                context.setAttribute(AUTH_SUCCESS_KEY, true)
                                chain.execute(context)
                            } else {
                                Mono.error(
                                    BusinessException(
                                        40006,
                                        "请求重复(Replay Attack)",
                                        HttpStatus.FORBIDDEN.value()
                                    )
                                )
                            }
                        }
                }
        }
    }
}