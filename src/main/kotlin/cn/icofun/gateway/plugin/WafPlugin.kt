package cn.icofun.gateway.plugin

import cn.icofun.gateway.core.GatewayContext
import cn.icofun.gateway.core.GatewayPlugin
import cn.icofun.gateway.core.PluginChain
import cn.icofun.gateway.model.StandardApiResponse
import cn.icofun.gateway.service.AttackLogService
import cn.icofun.gateway.service.WafRuleService
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Component
class WafPlugin(
    private val wafRuleService: WafRuleService,
    private val objectMapper: ObjectMapper,
    private val attackLogService: AttackLogService,
    private val meterRegistry: MeterRegistry
) : GatewayPlugin {
    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun getName(): String = "WafPlugin"
    override fun getOrder(): Int = -10
    override fun isCritical(): Boolean = true

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val exchange = context.exchange
        val request = exchange.request
        val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
        val traceId = request.headers.getFirst("X-Trace-Id") ?: "unknown"
        val routeId = route?.id ?: "unknown"

        val metadata = route?.metadata ?: emptyMap()
        val enabled = when (val value = metadata["waf_enabled"]) {
            is Boolean -> {
                logger.debug("[WAF] [TraceID: $traceId] [Route: $routeId] waf_enabled type: Boolean, value: $value")
                value.toString()
            }

            is String -> {
                logger.debug("[WAF] [TraceID: $traceId] [Route: $routeId] waf_enabled type: String, value: '$value'")
                value
            }

            else -> {
                logger.debug("[WAF] [TraceID: $traceId] [Route: $routeId] waf_enabled type: ${value?.javaClass?.name ?: "null"}, using default: true")
                "true"
            }
        }
        if ("false".equals(enabled, ignoreCase = true)) {
            logger.debug("[WAF] [TraceID: $traceId] [Route: $routeId] WAF disabled for this route")
            return chain.execute(context)
        }

        // 2. 准备检测数据源
        // 获取 GlobalBodyCachingFilter 缓存的 Body 字符串
        val cachedBody = exchange.attributes["cachedRequestBodyString"] as? String ?: ""
        logger.debug("[WAF] [TraceID: $traceId] [Route: $routeId] Checking request body, size: ${cachedBody.length} chars")

        val uriPath = request.uri.path
        val headerMap = HashMap<String, List<String>>()
        request.headers.forEach { key, value ->
            headerMap[key] = value
        }
        val queryMap = HashMap<String, List<String>>()
        request.queryParams.forEach { key, value ->
            queryMap[key] = value
        }
        return wafRuleService.getActiveRules()
            .doOnSubscribe {
                logger.debug("[WAF] [TraceID: $traceId] [Route: $routeId] Starting WAF rule evaluation...")
            }
            .doOnComplete {
                logger.debug("[WAF] [TraceID: $traceId] [Route: $routeId] All WAF rules passed, request allowed")
            }
            .filter { rule ->
                val isMatch = when (rule.matchField.uppercase()) {
                    "BODY" -> checkRisk(cachedBody, rule.pattern)
                    "URI" -> checkRisk(uriPath, rule.pattern)
                    "HEADER" -> headerMap.entries.any { entry ->
                        val key = entry.key
                        val values = entry.value
                        checkRisk(key, rule.pattern) || values.any { checkRisk(it, rule.pattern) }
                    }

                    "QUERY" -> queryMap.entries.any { entry ->
                        val key = entry.key
                        val values = entry.value
                        checkRisk(key, rule.pattern) || values.any { checkRisk(it, rule.pattern) }
                    }

                    else -> false // 未知域忽略
                }
                if (isMatch) {
                    logger.warn("[WAF] [TraceID: $traceId] [Route: $routeId] Rule matched: [${rule.id}] ${rule.ruleName} (Type: ${rule.ruleType}, Field: ${rule.matchField})")
                }
                isMatch
            }
            .next()
            .flatMap { matchedRule ->
                logger.warn("[WAF] [TraceID: $traceId] [Route: $routeId] 🛑 REQUEST BLOCKED - Rule: [${matchedRule.id}] ${matchedRule.ruleName}, Type: ${matchedRule.ruleType}, Priority: ${matchedRule.priority}")

                meterRegistry.counter(
                    "gateway.waf.blocked",
                    "rule_type", matchedRule.ruleType,
                    "route_id", routeId,
                    "rule_id", matchedRule.id.toString()
                ).increment()

                // 4. 异步记录攻击证据 (不阻塞当前请求)
                attackLogService.recordAttackAsync(
                    exchange = exchange,
                    ruleId = matchedRule.id,
                    ruleName = matchedRule.ruleName,
                    attackType = matchedRule.ruleType,
                    body = cachedBody
                )

                // 5. 构造标准阻断响应 (JSON 403)
                val response = exchange.response
                response.statusCode = HttpStatus.FORBIDDEN
                response.headers.contentType = MediaType.APPLICATION_JSON

                val result = StandardApiResponse.fail<Any>(40301, "WAF Intercepted: Malicious content detected.")
                val bytes = objectMapper.writeValueAsBytes(result)
                val buffer = response.bufferFactory().wrap(bytes)

                response.writeWith(Mono.just(buffer))
            }
            .switchIfEmpty(chain.execute(context))
    }

    private fun checkRisk(rawInput: String?, regex: String): Boolean {
        if (rawInput.isNullOrBlank()) return false

        var input = rawInput
        var decodeCount = 0
        try {
            while (decodeCount < 3) {
                val decoded = URLDecoder.decode(input, StandardCharsets.UTF_8)
                if (decoded == input) break
                input = decoded
                decodeCount++
            }
        } catch (_: Exception) {

        }

        return try {
            regex.toRegex(RegexOption.IGNORE_CASE).containsMatchIn(input!!)
        } catch (e: Exception) {
            logger.error("Invalid WAF Regex pattern: $regex", e)
            false
        }
    }
}