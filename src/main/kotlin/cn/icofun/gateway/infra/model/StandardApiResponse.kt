package cn.icofun.gateway.infra.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_EMPTY) // 关键注解：排除所有值为null的字段
data class StandardApiResponse<T>(
    @param:JsonProperty("code")
    var code: Int,
    @param:JsonProperty("message")
    var message: String,
    @param:JsonProperty("data")
    var data: T?,
    @param:JsonProperty("trace_id")
    var traceId: String,
    @param:JsonProperty("retry_after")
    var retryAfter: Int? = null,
    @param:JsonProperty("detail")
    var detail: String? = null
){
    companion object{
        fun <T> success(data: T): StandardApiResponse<T>{
            return StandardApiResponse(
                code = 200,
                message = "success",
                data = data,
                traceId = "",
            )
        }

        fun <T> fail(code: Int, message: String): StandardApiResponse<T> {
            return StandardApiResponse(
                code = code,
                message = message,
                data = null,
                traceId = ""
            )
        }
    }
}