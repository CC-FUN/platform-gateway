package com.example.demo

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

@Component("manualHealth")
class ManualHealthIndicator : HealthIndicator {
    // 默认是健康状态 (UP)
    private var isUp: Boolean = true

    fun setUp(up: Boolean) {
        this.isUp = up
    }

    override fun health(): Health {
        return if (isUp) {
            Health.up().withDetail("status", "Manual UP").build()
        } else {
            Health.down().withDetail("status", "Manual DOWN - Simulated Failure").build()
        }
    }
}