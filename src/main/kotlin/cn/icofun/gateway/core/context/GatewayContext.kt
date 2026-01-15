package cn.icofun.gateway.core.context

import org.springframework.web.server.ServerWebExchange
import java.util.concurrent.ConcurrentHashMap

data class GatewayContext(
    var exchange: ServerWebExchange,
    val attributes: MutableMap<String, Any> = ConcurrentHashMap(),
) {
    fun <T> getAttribute(key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return attributes[key] as? T
    }

    fun setAttribute(key: String, value: Any) {
        attributes[key] = value
    }
}