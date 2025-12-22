package cn.icofun.gateway.loadbalancer

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class HealthCheckManager {
    // 记录不健康的实例标识 (ip:port)
    private val unhealthyInstances = ConcurrentHashMap.newKeySet<String>()

    fun markUnhealthy(instanceId: String) {
        unhealthyInstances.add(instanceId)
    }

    fun markHealthy(instanceId: String) {
        unhealthyInstances.remove(instanceId)
    }

    fun isHealthy(instanceId: String): Boolean {
        return !unhealthyInstances.contains(instanceId)
    }

    fun getUnhealthyCount() = unhealthyInstances.size

    // 新增：获取当前所有不健康实例的副本
    fun getUnhealthyList(): Set<String> {
        return unhealthyInstances.toSet()
    }
}