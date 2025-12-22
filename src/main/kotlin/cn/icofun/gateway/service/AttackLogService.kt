package cn.icofun.gateway.service

import cn.icofun.gateway.model.entity.GatewayAttackLogEntity
import cn.icofun.gateway.model.entity.GatewayDlpRuleEntity
import cn.icofun.gateway.repository.GatewayAttackLogRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.LocalDateTime

@Service
class AttackLogService(
    private val attackLogRepository: GatewayAttackLogRepository,
    private val dlpRuleService: DlpRuleService
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    fun searchLogs(
        attackType: String?,
        clientIp: String?,
        startTime: LocalDateTime?,
        endTime: LocalDateTime?,
        page: Int,
        size: Int
    ): Mono<Map<String, Any>> {
        val effectiveAttackType = if (attackType.isNullOrBlank()) null else attackType
        val effectiveClientIp = if (clientIp.isNullOrBlank()) null else clientIp

        val start = startTime ?: LocalDateTime.now().minusDays(7)
        val end = endTime ?: LocalDateTime.now()
        val offset = (page - 1) * size.toLong()

        return attackLogRepository.countByCondition(effectiveAttackType, effectiveClientIp, start, end)
            .flatMap { total ->
                if (total > 0) {
                    attackLogRepository.findByCondition(
                        effectiveAttackType,
                        effectiveClientIp,
                        start,
                        end,
                        size,
                        offset
                    )
                        .collectList()
                        .map { list ->
                            mapOf(
                                "total" to total,
                                "records" to list,
                                "page" to page,
                                "size" to size
                            )
                        }
                } else {
                    Mono.just(
                        mapOf(
                            "total" to 0L,
                            "records" to emptyList<GatewayAttackLogEntity>(),
                            "page" to page,
                            "size" to size
                        )
                    )
                }
            }
    }

    fun getById(id: Long): Mono<GatewayAttackLogEntity> {
        return attackLogRepository.findById(id)
    }

    fun recordAttackAsync(
        exchange: ServerWebExchange,
        ruleId: Long?,
        ruleName: String?,
        attackType: String,
        body: String
    ) {
        dlpRuleService.getActiveRules()
            .flatMap { dlpRules ->
                Mono.fromCallable {
                    val request = exchange.request
                    val sb = StringBuilder()

                    sb.append("${request.method} ${request.uri.path} HTTP/1.1\n")

                    request.headers.forEach { (k, v) ->
                        sb.append("$k: ${v.joinToString(",")}\n")
                    }

                    sb.append("\n")
                    if (body.isNotEmpty()) {
                        sb.append(body)
                    }

                    val rawRequest = sb.toString()
                    val safeRawRequest = applyDynamicMasking(rawRequest, dlpRules)

                    GatewayAttackLogEntity(
                        traceId = exchange.getAttribute("traceId") ?: request.id,
                        ruleId = ruleId,
                        ruleName = ruleName,
                        attackType = attackType,
                        clientIp = request.remoteAddress?.address?.hostAddress ?: "unknown",
                        requestUri = request.uri.path,
                        requestMethod = request.method.name(),
                        rawRequest = safeRawRequest, // 存入脱敏后的安全数据
                        attackTime = LocalDateTime.now()
                    )
                }
                    .subscribeOn(Schedulers.boundedElastic()) // 耗时的正则匹配放在独立线程
            }
            .flatMap { entity ->
                attackLogRepository.save(entity)
            }
            .doOnError { e -> logger.error("Failed to record attack log", e) }
            .subscribe() // Fire-and-forget
    }

    /**
     * 根据 DLP 规则列表进行动态脱敏
     */
    private fun applyDynamicMasking(content: String, rules: List<GatewayDlpRuleEntity>): String {
        if (rules.isEmpty()) return content

        var processedContent = content
        for (rule in rules) {
            try {
                if (rule.regexPattern.isBlank()) continue

                // 编译正则 (忽略大小写)
                val regex = Regex(rule.regexPattern, RegexOption.IGNORE_CASE)

                // 执行替换：将匹配到的内容替换为 maskChar (例如 "****")
                // 这里简单处理：只要匹配到就全部掩盖。
                // 如果需要保留部分明文（如手机号前3后4），可以在前端配置复杂的正则 Lookaround (零宽断言)
                val mask = rule.maskChar.ifBlank { "*" }
                val replacement = mask.repeat(6)

                processedContent = regex.replace(processedContent, replacement)
            } catch (e: Exception) {
                // 单个规则正则错误不影响其他规则
                logger.warn("Invalid DLP Regex in AttackLog: ${rule.regexPattern}", e)
            }
        }
        return processedContent
    }
}