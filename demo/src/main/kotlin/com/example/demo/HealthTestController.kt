package com.example.demo

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/test/health")
class HealthTestController(private val manualHealthIndicator: ManualHealthIndicator) {

    // 模拟服务发生故障，调用后 /actuator/health 将返回 DOWN
    @PostMapping("/down")
    fun setDown(): String {
        manualHealthIndicator.setUp(false)
        return "Service health status set to DOWN (Simulation)"
    }

    // 模拟服务恢复正常，调用后 /actuator/health 将返回 UP
    @PostMapping("/up")
    fun setUp(): String {
        manualHealthIndicator.setUp(true)
        return "Service health status set to UP (Simulation)"
    }
}