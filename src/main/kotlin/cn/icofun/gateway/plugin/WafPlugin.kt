package cn.icofun.gateway.plugin

import cn.icofun.gateway.core.GatewayContext
import cn.icofun.gateway.core.GatewayPlugin
import cn.icofun.gateway.core.PluginChain
import cn.icofun.gateway.exception.BusinessException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

@Component
class WafPlugin : GatewayPlugin {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val SQL_INJECTION_REGEX =
        "\\b(" +
                "select\\s+.+\\s+from|" +  // 匹配 select ... from
                "insert\\s+into|" +         // 匹配 insert into
                "update\\s+.+\\s+set|" +    // 匹配 update ... set
                "delete\\s+from|" +         // 匹配 delete from
                "drop\\s+table|" +          // 匹配 drop table
                "truncate\\s+table|" +      // 匹配 truncate table
                "exec\\s+|" +               // 匹配 exec
                "execute\\s+|" +            // 匹配 execute
                "declare\\s+|" +            // 匹配 declare
                "union\\s+select" +         // 匹配 union select
                ")\\b"
    private val sqlPattern = Pattern.compile(SQL_INJECTION_REGEX, Pattern.CASE_INSENSITIVE)

    private val XSS_REGEX = "<script|javascript:|onload=|onerror=|onclick=|eval\\(|alert\\("
    private val xssPattern = Pattern.compile(XSS_REGEX, Pattern.CASE_INSENSITIVE)

    override fun getName(): String = "WafPlugin"
    override fun getOrder(): Int = -10
    override fun shouldSkip(context: GatewayContext): Boolean = false

    override fun execute(context: GatewayContext, chain: PluginChain): Mono<Void> {
        val request = context.exchange.request

        val queryParams = request.queryParams
        for ((key, values) in queryParams) {
            for (value in values) {
                if (checkRisk(value)) {
                    logger.warn("WAF blocked query param '$key': suspicious content detected")
                    return blockRequest()
                }
            }
        }

        val cachedBody = context.getAttribute<String>("cachedRequestBody")
        if (!cachedBody.isNullOrBlank()) {
            if (checkRisk(cachedBody)) {
                logger.warn("WAF blocked request body: suspicious content detected")
                return blockRequest()
            }
        }

        return chain.execute(context)
    }

    private fun blockRequest(): Mono<Void> {
        return Mono.error(BusinessException(40301, "非法请求：包含恶意字符", HttpStatus.BAD_REQUEST.value()))
    }

    private fun checkRisk(rawInput: String?): Boolean {
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

        if (sqlPattern.matcher(input).find()) {
            logger.debug("Matched SQL Regex: $input")
            return true
        }

        if (xssPattern.matcher(input).find()) {
            return true
        }

        return false
    }
}