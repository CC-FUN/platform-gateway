package cn.icofun.gateway.exception

import cn.icofun.gateway.i18n.I18nMessageUtils
import cn.icofun.gateway.model.StandardApiResponse
import cn.icofun.gateway.utils.MdcUtils
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.io.DecodingException
import org.slf4j.LoggerFactory
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import reactor.core.publisher.Mono

@Component
@Order(-2) // 优先级高于默认处理
class GlobalExceptionHandler(
    private val objectMapper: ObjectMapper,
    private val i18nMessageUtils: I18nMessageUtils
) : ErrorWebExceptionHandler {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun handle(exchange: ServerWebExchange, ex: Throwable): Mono<Void> {
        val response = exchange.response
        if (response.isCommitted) {
            return Mono.error(ex)
        }

        response.headers.contentType = MediaType.APPLICATION_JSON
        val request = exchange.request
        val traceId = request.headers.getFirst("X-Trace-Id") ?: MdcUtils.getTraceIdOrDefault()
        val path = request.path.value()
        val method = request.method.name()

        val (httpStatus, code, finalMessage) = when (ex) {
            is BusinessException -> {
                logger.warn("[ExceptionHandler] [TraceID: $traceId] BusinessException: code=${ex.code}, message=${ex.message}, path=$path")
                val msg = i18nMessageUtils.getMessage(ex.message, ex.args, request)
                Triple(HttpStatus.OK, ex.code, msg)
            }

            is ServerWebInputException -> {
                val cause = ex.cause
                logger.error("[ExceptionHandler] [TraceID: $traceId] ServerWebInputException: ${ex.message}, cause: ${cause?.javaClass?.name}, path=$path, method=$method", ex)
                val msg = if (cause is DecodingException) {
                    ExceptionMessageUtils.getJacksonErrorMessage(cause.message, i18nMessageUtils, request)
                } else {
                    ExceptionMessageUtils.getKeyErrorMessage(ex.message!!, i18nMessageUtils, request)
                }
                Triple(HttpStatus.BAD_REQUEST, 400, msg)
            }

            is ResponseStatusException -> {
                logger.warn("[ExceptionHandler] [TraceID: $traceId] ResponseStatusException: status=${ex.statusCode}, reason=${ex.reason}, path=$path")
                val status = HttpStatus.resolve(ex.statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
                val msgKey = ex.reason ?: "error.request.generic"
                val msg = i18nMessageUtils.getMessage(msgKey, null, request)
                Triple(status, ex.statusCode.value(), msg)
            }

            is NumberFormatException -> {
                logger.error("[ExceptionHandler] [TraceID: $traceId] NumberFormatException: ${ex.message}, path=$path", ex)
                val msg = ExceptionMessageUtils.getClassCastErrorMessage(ex.message, i18nMessageUtils, request)
                Triple(HttpStatus.BAD_REQUEST, 400, msg)
            }

            is ClassCastException -> {
                logger.error("[ExceptionHandler] [TraceID: $traceId] ClassCastException: ${ex.message}, path=$path, method=$method", ex)
                val msg = ExceptionMessageUtils.getClassCastErrorMessage(ex.message, i18nMessageUtils, request)
                Triple(HttpStatus.INTERNAL_SERVER_ERROR, 500, msg)
            }

            else -> {
                logger.error("[ExceptionHandler] [TraceID: $traceId] Uncaught ${ex.javaClass.name}: ${ex.message}, path=$path, method=$method", ex)
                val msg = i18nMessageUtils.getMessage("error.generic", null, request)
                Triple(HttpStatus.INTERNAL_SERVER_ERROR, 500, msg)
            }
        }

        response.statusCode = HttpStatus.resolve(httpStatus.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR

        val result = StandardApiResponse.fail<Any>(code, finalMessage).apply {
            val traceId = traceId
        }

        logger.debug("[ExceptionHandler] [TraceID: $traceId] Returning error response: code=$code, message=$finalMessage")

        return try {
            val bytes = objectMapper.writeValueAsBytes(result)
            val buffer = response.bufferFactory().wrap(bytes)
            response.writeWith(Mono.just(buffer))
        } catch (_: JsonProcessingException) {
            response.statusCode = HttpStatus.INTERNAL_SERVER_ERROR
            Mono.empty()
        }
    }
}