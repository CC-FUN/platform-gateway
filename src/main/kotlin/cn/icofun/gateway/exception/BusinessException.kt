package cn.icofun.gateway.exception

import org.springframework.http.HttpStatus


data class BusinessException(
    val code: Int, // 业务状态码（如4005）
    override val message: String, // 用户可见的错误信息（如“测试用户不存在”）
    val httpStatus: Int = HttpStatus.BAD_REQUEST.value(), // HTTP状态码（默认400）
    val detail: String? = null // 技术细节（可选，如“用户表查询失败”）
) : RuntimeException(message)