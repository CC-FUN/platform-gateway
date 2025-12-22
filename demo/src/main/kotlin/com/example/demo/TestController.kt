package com.example.demo
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.InetAddress
import java.util.*

@RestController
@RequestMapping("/api")
class TestController {

    // 获取当前实例运行的端口
    @Value("\${server.port}")
    private var port: String? = null

    // 获取 Nacos 中配置的灰度版本（对应 GrayLoadBalancer 逻辑中的 metadata["version"]）
    // 如果没有配置，默认显示 v1
    @Value("\${spring.cloud.nacos.discovery.metadata.version:v1}")
    private var version: String? = null

//    @PostMapping("/test")
    @GetMapping("/test")
    fun test(): Map<String, Any> {
        val response = mutableMapOf<String, Any>()

        response["message"] = "Hello from User Service!"
        response["timestamp"] = System.currentTimeMillis()

        // 核心识别信息：当前实例的 IP 和 端口
        val hostAddress = InetAddress.getLocalHost().hostAddress
        response["instance"] = "$hostAddress:$port"

        // 灰度版本信息：用于验证 GrayLoadBalancer 的过滤逻辑
        response["version"] = version ?: "unknown"

        // 随机 ID：用于观察请求是否被负载均衡切换（或者是被网关缓存了）
        response["requestId"] = UUID.randomUUID().toString()

        return response
    }
}