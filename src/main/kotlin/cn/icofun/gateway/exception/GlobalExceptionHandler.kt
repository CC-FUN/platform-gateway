package cn.icofun.gateway.exception

import cn.icofun.gateway.i18n.I18nMessageUtils
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler
//import org.springframework.boot.webflux.error.ErrorWebExceptionHandler
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono


@Configuration
@Order(-2)
class GlobalExceptionHandler(
    private val i18nMessageUtils: I18nMessageUtils,
    private val objectMapper: ObjectMapper,
) : ErrorWebExceptionHandler {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    override fun handle(exchange: ServerWebExchange, ex: Throwable): Mono<Void> {
        val response = exchange.response
        if (response.isCommitted) {
            return Mono.error(ex)
        }

        response.headers.contentType = MediaType.APPLICATION_JSON

        val httpStatus: Int
        val businessCode: Int
        val finalMessage: String

        when (ex) {
            is ResponseStatusException -> {
                httpStatus = ex.statusCode.value()
                businessCode = httpStatus
                val key = if (httpStatus == 404) "error.resource.not.found" else "error.status"
                finalMessage = i18nMessageUtils.getMessage(key, exchange.request, ex.reason ?: "Request Error")
            }

            is BusinessException -> {
                httpStatus = ex.httpStatus
                businessCode = ex.code
                finalMessage = ex.message
            }

            else -> {
                logger.error("System Error: ${exchange.request.path}", ex)
                httpStatus = HttpStatus.INTERNAL_SERVER_ERROR.value()
                businessCode = HttpStatus.INTERNAL_SERVER_ERROR.value()
                finalMessage = i18nMessageUtils.getMessage("error.generic", exchange.request, "Internal Server Error")
            }
        }

        try {
            response.setRawStatusCode(httpStatus)
        } catch (e: IllegalArgumentException) {
            response.setRawStatusCode(500)
            logger.error("Invalid HTTP Status Code: $httpStatus", e)
        }

        val errorResult = mapOf(
            "code" to businessCode,
            "message" to finalMessage,
            "path" to exchange.request.path.value(),
            "timestamp" to System.currentTimeMillis()
        )

        return response.writeWith(Mono.fromSupplier {
            val bufferFactory: DataBufferFactory = response.bufferFactory()
            try {
                val bytes = objectMapper.writeValueAsBytes(errorResult)
                bufferFactory.wrap(bytes)
            } catch (_: Exception) {
                bufferFactory.wrap(ByteArray(0))
            }
        })
    }

//    companion object {
//        // 国际化消息Key前缀（统一管理）
//        private const val ERROR_GENERIC = "error.generic"
//        private const val ERROR_STATUS = "error.status"
//        private const val ERROR_RESOURCE_NOT_FOUND = "error.resource.not.found"
//        private const val ERROR_NUMBER_FORMAT = "error.number.format"
//        private const val ERROR_MISSING_PARAMETER = "error.missing.parameter"
//        private const val ERROR_ACCESS_DENIED = "error.access.denied"
//        private const val ERROR_PARAMETER_TYPE = "error.parameter.type"
//        private const val ERROR_AUTHENTICATION = "error.authentication"
//        private const val ERROR_REQUEST_TIMEOUT = "error.request.timeout"
//        private const val ERROR_REQUEST_BODY = "error.request.body"
//        private const val ERROR_ILLEGAL_STATE = "error.illegal.state"
//        private const val ERROR_BUSINESS = "error.business"
//        private const val ERROR_BUSINESS_DETAIL = "error.business.detail"
//        private const val ERROR_STATUS_DETAIL = "error.status.detail"
//        private const val ERROR_RESOURCE_NOT_FOUND_DETAIL = "error.resource.not.found.detail"
//        private const val ERROR_NUMBER_FORMAT_DETAIL = "error.number.format.detail"
//        private const val ERROR_MISSING_PARAMETER_DETAIL = "error.missing.parameter.detail"
//        private const val ERROR_ACCESS_DENIED_DETAIL = "error.access.denied.detail"
//        private const val ERROR_PARAMETER_TYPE_DETAIL = "error.parameter.type.detail"
//        private const val ERROR_AUTHENTICATION_DETAIL = "error.authentication.detail"
//        private const val ERROR_REQUEST_TIMEOUT_DETAIL = "error.request.timeout.detail"
//        private const val ERROR_REQUEST_BODY_DETAIL = "error.request.body.detail"
//        private const val ERROR_ILLEGAL_STATE_DETAIL = "error.illegal.state.detail"
//        private const val ERROR_GENERIC_DETAIL_PROD = "error.generic.exception.detail.prod"
//        private const val ERROR_GENERIC_DETAIL_DEV = "error.generic.exception.detail.dev"
//        private const val ERROR_VALIDATION = "error.validation.failed"
//        private const val ERROR_VALIDATION_DETAIL = "error.validation.detail"
//        private const val ERROR_READ_BODY = "error.read.body" // 新增：读取请求体错误的国际化Key
//        private const val ERROR_READ_BODY_DETAIL = "error.read.body.detail" // 新增：详情Key
//        private const val ERROR_JSON_INVALID = "error.json.invalid"
//        private const val ERROR_JSON_INVALID_DETAIL = "error.json.invalid.detail"
//        private const val ERROR_TYPE_CAST = "error.type.cast"
//        private const val ERROR_TYPE_CAST_STANDARAPI = "error.type.cast.standardapi.response"
//        private const val ERROR_TYPE_CAST_UNKNOWN = "error.type.cast.unknown"
//        private const val ERROR_TYPE_CAST_GENERIC = "error.type.cast.generic"
//
//    }
//
//    /**
//     * 构建异常响应（统一方法）
//     * ✅ 使用 MdcUtils 替代原来的 getRequestId()
//     */
//    private fun buildErrorResponse(
//        code: Int,
//        message: String,
//        detail: String,
//        httpStatus: HttpStatusCode,
//        retryAfter: Int? = null
//    ): ResponseEntity<StandardApiResponse<Nothing>> {
//        return ResponseEntity(
//            StandardApiResponse(
//                code = code,
//                message = message,
//                data = null,
//                traceId = MdcUtils.getTraceIdOrDefault(),
//                detail = detail,
//                retryAfter = retryAfter,
//            ),
//            httpStatus
//        )
//    }
//
//    /**
//     * 1. 处理Spring内置状态码异常（如400、404、500等）
//     * 自动继承原异常的HTTP状态码和Reason
//     */
//    @ExceptionHandler(ResponseStatusException::class)
//    fun handleResponseStatusException(
//        ex: ResponseStatusException,
//    ): ResponseEntity<StandardApiResponse<Nothing>> {
//        val message = ERROR_STATUS
//
//        logger.error(
//            "捕捉ResponseStatusException | statusCode: {}, reason: {}",
//            ex.statusCode, ex.reason, ex
//        )
//
//        val detail = ExceptionMessageUtils.buildDetailWithArgs(
//            ERROR_STATUS_DETAIL,
//            ex.statusCode,
//            ex.reason
//        )
//        return buildErrorResponse(
//            code = ex.statusCode.value(),
//            message = message,
//            detail = detail,
//            httpStatus = ex.statusCode
//        )
//    }
//
//    /**
//     * 2. 处理自定义业务异常（携带业务状态码、HTTP状态、详情）
//     * 保留原BusinessException的所有元数据
//     */
//    @ExceptionHandler(BusinessException::class)
//    fun handleBusinessException(
//        ex: BusinessException,
//    ): ResponseEntity<StandardApiResponse<Nothing>> {
//        val message = ERROR_BUSINESS
//
//        val detail = ExceptionMessageUtils.buildDetailWithArgs(
//            ERROR_BUSINESS_DETAIL,
//            ex.code,
//            ex.message,
//            ex.httpStatus,
//            ex.detail
//        )
//        logger.error(
//            "捕获BusinessException | code: {}, detail: {}",
//            ex.code, ex.detail, ex
//        )
//        return buildErrorResponse(
//            code = ex.code,
//            message = message,
//            detail = detail,
//            httpStatus = HttpStatus.valueOf(ex.httpStatus)
//        )
//    }
//
//    /**
//     * 统一处理参数验证相关的异常
//     * 包括：参数缺失、类型不匹配、格式错误、验证失败等
//     */
//    @ExceptionHandler(
//        MissingServletRequestParameterException::class,
//        MethodArgumentTypeMismatchException::class,
//        IllegalArgumentException::class,
//        MethodArgumentNotValidException::class,
//        NumberFormatException::class
//    )
//    fun handleParameterValidationExceptions(
//        ex: Exception,
//        request: HttpServletRequest
//    ): ResponseEntity<StandardApiResponse<Nothing>> {
//        val (message, detail) = when (ex) {
//            is MissingServletRequestParameterException -> {
//                logger.warn("缺少必填参数 | 参数名: {}, 参数类型: {}", ex.parameterName, ex.parameterType, ex)
//
//                Pair(
//                    ERROR_MISSING_PARAMETER,
//                    ExceptionMessageUtils.buildDetailWithArgs(
//                        ERROR_MISSING_PARAMETER_DETAIL,
//                        ex.parameterName,
//                        ex.parameterType.substringAfterLast(".")
//                    )
//                )
//
//            }
//
//            is MethodArgumentTypeMismatchException -> {
//                val actualTypeName = ex.value?.javaClass?.simpleName
//                logger.warn(
//                    "参数类型不匹配 | 参数名: {}, 期望类型: {}, 实际类型: {}",
//                    ex.name, ex.requiredType?.simpleName, actualTypeName, ex
//                )
//
//                Pair(
//                    ERROR_PARAMETER_TYPE,
//                    ExceptionMessageUtils.buildDetailWithArgs(
//                        ERROR_PARAMETER_TYPE_DETAIL,
//                        ex.name,
//                        ex.requiredType?.simpleName,
//                        actualTypeName
//                    )
//                )
//            }
//
//            is MethodArgumentNotValidException -> {
//                val localizedErrors = ex.bindingResult.fieldErrors[0].defaultMessage
//
//                logger.warn(
//                    "参数验证失败 | 请求URL: {}, 方法: {}, 错误信息: {}",
//                    request.requestURL, request.method, localizedErrors, ex
//                )
//
//                Pair(
//                    ERROR_VALIDATION,
//                    ExceptionMessageUtils.buildDetailWithArgs(
//                        localizedErrors!!
//                    )
//                )
//            }
//
//            is NumberFormatException -> {
//                val illegalValue = ExceptionMessageUtils.parseIllegalValueFromNumberException(ex.message)
//                logger.warn("参数格式错误 | 非法值: {}, 异常信息: {}", illegalValue, ex.message, ex)
//
//                Pair(
//                    ERROR_NUMBER_FORMAT,
//                    ExceptionMessageUtils.buildDetailWithArgs(
//                        ERROR_NUMBER_FORMAT_DETAIL,
//                        illegalValue
//                    )
//                )
//            }
//
//            else -> {
//                logger.warn("参数验证异常 | 类型: {}, 原因: {}", ex.javaClass.simpleName, ex.message, ex)
//
//                Pair(
//                    ERROR_VALIDATION,
//                    ExceptionMessageUtils.buildDetailWithArgs(
//                        ERROR_VALIDATION_DETAIL,
//                        ex.message
//                    )
//                )
//            }
//        }
//
//        return buildErrorResponse(
//            code = HttpStatus.BAD_REQUEST.value(),
//            message = message,
//            detail = detail,
//            httpStatus = HttpStatus.BAD_REQUEST
//        )
//    }
//
//    /**
//     * 统一处理认证和权限相关的异常
//     * 包括：权限不足、认证失败等
//     */
//    @ExceptionHandler(
//        AccessDeniedException::class,
//        AuthenticationException::class
//    )
//    fun handleAuthExceptions(
//        ex: Exception,
//    ): ResponseEntity<StandardApiResponse<Nothing>> {
//
//        val (message, detail, httpStatus) = when (ex) {
//            is AccessDeniedException -> {
//                logger.warn("权限不足 | 原因: {}", ex.message, ex)
//                Triple(
//                    ERROR_ACCESS_DENIED,
//                    ExceptionMessageUtils.buildDetailWithArgs(
//                        ERROR_ACCESS_DENIED_DETAIL,
//                        ex.message
//                    ),
//                    HttpStatus.FORBIDDEN
//                )
//            }
//
//            is AuthenticationException -> {
//                logger.warn("认证失败 | 原因: {}", ex.message, ex)
//                Triple(
//                    ERROR_AUTHENTICATION,
//                    ExceptionMessageUtils.buildDetailWithArgs(
//                        ERROR_AUTHENTICATION_DETAIL,
//                        ex.message
//                    ),
//                    HttpStatus.UNAUTHORIZED
//                )
//            }
//
//            else -> {
//                logger.warn("认证异常 | 类型: {}", ex.javaClass.simpleName, ex)
//                Triple(
//                    ERROR_ACCESS_DENIED,
//                    ExceptionMessageUtils.buildDetailWithArgs(
//                        ERROR_ACCESS_DENIED_DETAIL,
//                        ex.message
//                    ),
//                    HttpStatus.FORBIDDEN
//                )
//            }
//        }
//
//        return buildErrorResponse(
//            code = httpStatus.value(),
//            message = message,
//            detail = detail,
//            httpStatus = httpStatus
//        )
//    }
//
//    /**
//     * 11. 处理请求超时（SocketTimeoutException）
//     * 对应网关或下游服务超时
//     */
//    @ExceptionHandler(SocketTimeoutException::class)
//    fun handleSocketTimeout(
//        ex: SocketTimeoutException,
//    ): ResponseEntity<StandardApiResponse<Nothing>> {
//        val message = ERROR_REQUEST_TIMEOUT
//        val detail = ExceptionMessageUtils.buildDetailWithArgs(
//            ERROR_REQUEST_TIMEOUT_DETAIL,
//            ex.message
//        )
//
//
//        logger.warn(
//            "请求超时 | 原因: {}",
//            ex.message, ex
//        )
//
//        return buildErrorResponse(
//            code = HttpStatus.GATEWAY_TIMEOUT.value(),
//            message = message,
//            detail = detail,
//            httpStatus = HttpStatus.GATEWAY_TIMEOUT,
//            retryAfter = 60
//        )
//    }
//
//    /**
//     * 12. 处理请求体格式错误（HttpMessageNotReadableException）
//     * 对应JSON/XML解析失败
//     */
//    @ExceptionHandler(HttpMessageNotReadableException::class, JsonParseException::class)
//    fun handleHttpMessageNotReadable(
//        ex: Exception,
//        request: HttpServletRequest
//    ): ResponseEntity<StandardApiResponse<Nothing>> {
//
//        val (message, detail) = when (ex) {
//            is JsonParseException -> {
//                logger.warn(
//                    "JSON 解析失败 - 路径: {}, 位置: 行{}, 列{}",
//                    request.requestURI, ex.location?.lineNr, ex.location?.columnNr, ex
//                )
//
//                Pair(
//                    ERROR_JSON_INVALID,
//                    ExceptionMessageUtils.buildDetailWithArgs(
//                        ERROR_JSON_INVALID_DETAIL,
//                        ex.location.lineNr,
//                        ex.location.columnNr
//                    )
//                )
//            }
//
//            is HttpMessageNotReadableException -> {
//                logger.warn(
//                    "请求体格式错误 - 路径: {}",
//                    request.requestURI,
//                    ex
//                )
//
//                val cause = ex.cause
//                if (cause is JsonParseException) {
//
//                    Pair(
//                        ERROR_JSON_INVALID,
//                        ExceptionMessageUtils.buildDetailWithArgs(
//                            ERROR_JSON_INVALID_DETAIL,
//                            cause.location.lineNr,
//                            cause.location.columnNr
//                        )
//                    )
//                } else {
//                    val parseMessage = ExceptionMessageUtils.parseJacksonErrorMessage(ex.message)
//
//                    Pair(
//                        ERROR_REQUEST_BODY,
//                        ExceptionMessageUtils.buildDetailWithArgs(
//                            ERROR_REQUEST_BODY_DETAIL,
//                            parseMessage
//                        )
//                    )
//
//                }
//            }
//
//            else -> {
//                logger.warn("请求体解析异常 - 路径: {}", request.requestURI, ex)
//                Pair(
//                    ERROR_REQUEST_BODY,
//                    ExceptionMessageUtils.buildDetailWithArgs(
//                        ERROR_REQUEST_BODY_DETAIL,
//                        ex.message
//                    )
//                )
//            }
//        }
//
//        return buildErrorResponse(
//            code = HttpStatus.BAD_REQUEST.value(),
//            message = message,
//            detail = detail,
//            httpStatus = HttpStatus.BAD_REQUEST
//        )
//    }
//
//    /**
//     * 13. 处理非法状态异常（IllegalStateException）
//     * 对应对象状态不符合操作要求（如重复提交）
//     */
//    @ExceptionHandler(IllegalStateException::class)
//    fun handleIllegalState(
//        ex: IllegalStateException,
//    ): ResponseEntity<StandardApiResponse<Nothing>> {
//        val simplifiedMessage = ExceptionMessageUtils.extractKeyErrorMessage(ex.message ?: "")
//        val message = ERROR_ILLEGAL_STATE
//        val detail = ExceptionMessageUtils.buildDetailWithArgs(
//            ERROR_ILLEGAL_STATE_DETAIL,
//            simplifiedMessage
//        )
//
//        logger.error(
//            "非法状态异常 | 原因: {}",
//            ex.message,
//            ex
//        )
//        return buildErrorResponse(
//            code = HttpStatus.BAD_REQUEST.value(),
//            message = message,
//            detail = detail,
//            httpStatus = HttpStatus.BAD_REQUEST,
//        )
//    }
//
//    /**
//     * 17. 处理请求体读取异常（IOException）
//     * 对应CachingRequestBodyFilter中读取请求体失败的场景
//     */
//    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
//    fun handleMethodNotSupported(
//        ex: HttpRequestMethodNotSupportedException,
//        request: HttpServletRequest
//    ): ResponseEntity<StandardApiResponse<Nothing>> {
//        val supportedMethods = ex.supportedMethods ?: emptyArray()
//
//        val message = ERROR_READ_BODY
//
//        logger.warn(
//            "HTTP方法不支持 - 路径: {}, 方法: {}, 支持的方法: {}",
//            request.requestURI,
//            request.method,
//            supportedMethods.joinToString(", "),
//            ex
//        )
//
//        val detail = ExceptionMessageUtils.buildDetailWithArgs(
//            ERROR_READ_BODY_DETAIL,
//            request.method,
//            supportedMethods.joinToString(", ")
//        )
//
//        return buildErrorResponse(
//            code = HttpStatus.INTERNAL_SERVER_ERROR.value(),
//            message = message,
//            detail = detail,
//            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR,
//        )
//    }
//
//    /**
//     * 专门处理类型转换异常（ClassCastException）
//     * 这通常发生在响应体类型不匹配时
//     */
//    @ExceptionHandler(ClassCastException::class)
//    fun handleClassCastException(
//        ex: ClassCastException,
//        request: HttpServletRequest
//    ): ResponseEntity<StandardApiResponse<Nothing>> {
//        logger.error(
//            "类型转换异常 - 路径: {}, 方法: {}",
//            request.requestURI, request.method, ex
//        )
//        val (message, detailKey, args) = ExceptionMessageUtils.parseClassCastError(
//            ex.message!!,
//            ERROR_TYPE_CAST,
//            ERROR_TYPE_CAST_STANDARAPI,
//            ERROR_TYPE_CAST_GENERIC,
//            ERROR_TYPE_CAST_UNKNOWN
//        )
//
//        val detail = if (args != null) {
//            ExceptionMessageUtils.buildDetailWithArgs(
//                detailKey,
//                *args
//            )
//        } else {
//            detailKey
//        }
//
//        return buildErrorResponse(
//            code = HttpStatus.INTERNAL_SERVER_ERROR.value(),
//            message = message,
//            detail = detail,
//            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR
//        )
//    }
//
//
//    @ExceptionHandler(Exception::class)
//    fun handleGenericException(
//        ex: Exception,
//    ): ResponseEntity<StandardApiResponse<Nothing>> {
//        val isResourceNotFound = ex is NoResourceFoundException
//        val status = if (isResourceNotFound) HttpStatus.NOT_FOUND else HttpStatus.INTERNAL_SERVER_ERROR
//        val messageKey = if (isResourceNotFound) ERROR_RESOURCE_NOT_FOUND else ERROR_GENERIC
//
//        val exceptionMessage = ex.message ?: "网关内部错误"
//
//        val isProd = ExceptionMessageUtils.isProduction()
//
//        val detail = if (isProd) {
//            val prodDetailKey = if (isResourceNotFound) ERROR_RESOURCE_NOT_FOUND_DETAIL else ERROR_GENERIC_DETAIL_PROD
//            ExceptionMessageUtils.buildDetailWithArgs(
//                prodDetailKey, exceptionMessage
//            )
//        } else {
//            if (!isResourceNotFound) {
//                logger.error("系统通用异常", ex)
//            }
//
//            val devDetailKey = if (isResourceNotFound) ERROR_RESOURCE_NOT_FOUND_DETAIL else ERROR_GENERIC_DETAIL_DEV
//            ExceptionMessageUtils.buildDetailWithArgs(
//                devDetailKey, exceptionMessage
//            )
//        }
//
//        return buildErrorResponse(
//            code = status.value(),
//            message = messageKey,
//            detail = detail,
//            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR,
//        )
//    }
}