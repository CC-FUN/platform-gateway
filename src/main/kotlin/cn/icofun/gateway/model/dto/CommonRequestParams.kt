package cn.icofun.gateway.model.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class CommonRequestParams(
    @param:NotBlank(message = "app_info 不能为空")
    @param:Pattern(
        regexp = "^(browser|ios|android|win|macos)\\.\\d+(?:\\.\\d+){0,2}(?:-([a-zA-Z0-9-]+(?:[a-zA-Z0-9-]+)*))?\\.\\d+$",
        message = "app_info格式错误（示例：browser.1.0.0）"
    )
    val appInfo: String,

    @param:NotBlank
    @param:Pattern(
        regexp = "^\\d{13}$",
        message = "timestamp必须是13位毫秒级数字"
    )
    val timestamp: String,

    @param:NotBlank
    @param:Size(min = 8, max = 32, message = "nonce长度需8-32位")
    @param:Pattern(regexp = "^[a-zA-Z0-9]+$", message = "nonce只能包含字母和数字")
    val nonce: String,

    @param:NotBlank
    val traceId: String?,

    @param:NotBlank
    val sign: String
)