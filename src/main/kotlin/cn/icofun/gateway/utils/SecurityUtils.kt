package cn.icofun.gateway.utils

import cn.icofun.gateway.exception.BusinessException
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import reactor.core.publisher.Mono

/**
 * Spring Security 反应式安全上下文工具类
 */
object SecurityUtils {

    /**
     * 获取当前登录用户的用户名
     * 如果未登录或获取失败，抛出 401 异常
     */
    fun getCurrentUsername(): Mono<String> {
        return ReactiveSecurityContextHolder.getContext()
            .map { it.authentication?.principal as String }
            .switchIfEmpty(Mono.error(BusinessException(401, "未获取到认证信息")))
    }
}