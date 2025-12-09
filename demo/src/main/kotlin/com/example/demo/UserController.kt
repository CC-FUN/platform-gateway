package com.example.demo

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/users")
class UserController {

    @Value("\${spring.cloud.nacos.discovery.metadata.version:default}")
    private val version: String? = null

    @GetMapping("/info")
    fun info(): Map<String, Any> {
        return mapOf(
            "code" to 200,
            "message" to "我是 User-Service [$version] 版本", // 返回版本信息
            "data" to mapOf("name" to "张三", "version" to version)
        )
    }
}