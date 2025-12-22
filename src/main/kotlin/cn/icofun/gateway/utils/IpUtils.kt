package cn.icofun.gateway.utils

import org.springframework.web.server.ServerWebExchange
import java.net.InetSocketAddress

object IpUtils {

    /**
     * 获取客户端真实 IP 地址
     * 优先解析 X-Forwarded-For 头，以支持 Nginx/Docker 等反向代理场景
     */
    fun getClientIp(exchange: ServerWebExchange): String {
        val headers = exchange.request.headers
        val xForwardedFor = headers.getFirst("X-Forwarded-For")

        if (!xForwardedFor.isNullOrBlank()) {
            // 多级代理时，X-Forwarded-For 可能包含多个 IP，第一个通常是真实客户端 IP
            return xForwardedFor.split(",")[0].trim()
        }

        val remoteAddress: InetSocketAddress? = exchange.request.remoteAddress
        return remoteAddress?.address?.hostAddress ?: "unknown"
    }
}