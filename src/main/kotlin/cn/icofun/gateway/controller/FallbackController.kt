package cn.icofun.gateway.controller

import cn.icofun.gateway.i18n.I18nMessageUtils
import cn.icofun.gateway.model.dto.CommonRequestParams
import cn.icofun.gateway.model.dto.StandardApiResponse
import cn.icofun.gateway.utils.MdcUtils
import cn.icofun.gateway.utils.RequestBodyUtils
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@RestController
class FallbackController(
    private val i18nMessageUtils: I18nMessageUtils,
    private val objectMapper: ObjectMapper,
) {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val targetParamNames = listOf(
        "app_info", "appInfo", // 兼容下划线和驼峰
        "timestamp",
        "nonce",
        "trace_id", "traceId",
        "sign"
    )

    @GetMapping("/fallback")
    fun userFallback(exchange: ServerWebExchange): Mono<StandardApiResponse<Any>> {

        val currentTraceId = MdcUtils.getTraceIdOrDefault()

        return RequestBodyUtils.extractParams(exchange, objectMapper, targetParamNames)
            .flatMap { paramMap ->
                val commonParams = try {
                    CommonRequestParams(
                        appInfo = paramMap["app_info"] ?: paramMap["appInfo"] ?: "",
                        timestamp = paramMap["timestamp"] ?: "",
                        nonce = paramMap["nonce"] ?: "",
                        traceId = paramMap["trace_id"] ?: paramMap["traceId"], // 这是可空的 String?
                        sign = paramMap["sign"] ?: ""
                    )
                } catch (e: Exception) {
                    logger.warn("参数转换失败", e)
                    CommonRequestParams("", "", "", null, "")
                }

                logger.warn(
                    "⚡️ 触发服务降级 | Path: {} | AppInfo: {} | TraceID: {}",
                    exchange.request.path,
                    commonParams.appInfo.ifBlank { "UNKNOWN" },
                    MdcUtils.getTraceIdOrDefault()
                )

                val fallbackData = mapOf(
                    "service" to "Gateway",
                    "status" to "Degraded",
                    "reason" to "Upstream Service Unavailable or Timed Out",
                    "timestamp" to System.currentTimeMillis()
                )
                val msg = i18nMessageUtils.getMessage(
                    "error.service.busy", // Key
                    exchange.request,
                    "Service is busy, please try again later()"
                )


                val response = StandardApiResponse.success<Any>(fallbackData).apply {
                    this.code = 503
                    this.message = msg
                    this.traceId =currentTraceId
                }

                Mono.just(response)
            }
            .defaultIfEmpty(
                StandardApiResponse.fail<Any>(503,
                    i18nMessageUtils.getMessage("error.service.busy", exchange.request, "Service is busy")
                ).apply {
                    this.traceId = currentTraceId
                }
            )
    }
}