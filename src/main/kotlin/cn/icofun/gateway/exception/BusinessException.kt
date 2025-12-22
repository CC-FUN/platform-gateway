package cn.icofun.gateway.exception

import org.springframework.http.HttpStatus


data class BusinessException(
    val code: Int,
    override val message: String,
    val httpStatus: Int = HttpStatus.BAD_REQUEST.value(),
    val detail: String? = null,
    val args: Array<out Any>? = null
    ) : RuntimeException(message) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BusinessException

        if (code != other.code) return false
        if (httpStatus != other.httpStatus) return false
        if (message != other.message) return false
        if (!args.contentEquals(other.args)) return false
        if (detail != other.detail) return false

        return true
    }

    override fun hashCode(): Int {
        var result = code
        result = 31 * result + httpStatus
        result = 31 * result + message.hashCode()
        result = 31 * result + (args?.contentHashCode() ?: 0)
        result = 31 * result + (detail?.hashCode() ?: 0)
        return result
    }
}