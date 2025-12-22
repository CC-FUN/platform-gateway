package cn.icofun.gateway.controller

import cn.icofun.gateway.loadbalancer.HealthCheckManager
import cn.icofun.gateway.model.StandardApiResponse
import com.alibaba.cloud.nacos.NacosServiceManager
import com.alibaba.nacos.api.naming.NamingService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

@RestController
@RequestMapping("/actuator/gateway/monitor/instances")
class InstanceMonitorController(
    private val healthCheckManager: HealthCheckManager,
    @field:Autowired(required = false) private val nacosServiceManager: NacosServiceManager?
) {

    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * 获取当前所有离群（不健康）的实例 ID 列表
     * 对接前端：getUnhealthyInstances()
     */
    @GetMapping("/unhealthy")
    fun getUnhealthyInstances(): Mono<StandardApiResponse<Set<String>>> {
        // 直接从 HealthCheckManager 获取内存中的黑名单
        val unhealthyList = healthCheckManager.getUnhealthyList()
        return Mono.just(StandardApiResponse.success(unhealthyList))
    }

    /**
     * 获取离群实例总数 (给 Dashboard 大盘展示)
     */
    @GetMapping("/unhealthy/count")
    fun getUnhealthyCount(): Mono<StandardApiResponse<Int>> {
        return Mono.just(StandardApiResponse.success(healthCheckManager.getUnhealthyCount()))
    }

    /**
     * 手动恢复某个实例（将其从黑名单中移除）
     * 对接前端：recoverInstance(instanceId)
     */
    @PostMapping("/recover")
    fun recoverInstance(@RequestParam instanceId: String): Mono<StandardApiResponse<String>> {
        healthCheckManager.markHealthy(instanceId)
        return Mono.just(StandardApiResponse.success("实例 $instanceId 已尝试手动恢复，将重新进入健康检查队列"))
    }

    /**
     * 新增 修改实例权重 (对接 Registry 页面滑块)
     * 原理：通过 Nacos NamingService 修改实例的 Metadata 和原生 Weight
     */
    @PostMapping("/weight")
    fun updateInstanceWeight(
        @RequestParam serviceId: String,
        @RequestParam instanceId: String, // 格式: ip:port
        @RequestParam weight: Double
    ): Mono<StandardApiResponse<String>> {
        if (nacosServiceManager == null) {
            return Mono.error(RuntimeException("Nacos 服务未配置，无法动态修改权重"))
        }

        // Nacos 操作涉及网络 IO，使用 boundedElastic 线程池避免阻塞 Netty
        return Mono.fromCallable {
            val namingService: NamingService = nacosServiceManager.namingService

            val ip: String
            val port: Int

            if (instanceId.contains("#")) {
                // 处理 Nacos 长 ID: 172.31.16.1#8081##DEFAULT_GROUP@@user-service
                // 使用正则表达式或 split("#") 提取前两部分
                val parts = instanceId.split("#")
                ip = parts[0]
                port = parts[1].toInt()
            } else if (instanceId.contains(":")) {
                // 处理标准格式: 172.31.16.1:8081
                val parts = instanceId.split(":")
                ip = parts[0]
                port = parts[1].toInt()
            } else {
                throw IllegalArgumentException("无法识别的 InstanceId 格式: $instanceId")
            }

            // 2. 从 Nacos 获取该服务的所有实例，找到目标实例
            val instances = namingService.getAllInstances(serviceId)
            val targetInstance = instances.find { it.ip == ip && it.port == port }
                ?: throw RuntimeException("在 Nacos 中未找到实例: $serviceId -> $instanceId")

            // 3. 修改元数据 (GrayLoadBalancer 读取的是 metadata["weight"])
            val metadata = targetInstance.metadata.toMutableMap()
            // 存成整数型字符串，方便后续计算
            val weightInt = weight.toInt()
            metadata["weight"] = weightInt.toString()
            targetInstance.metadata = metadata

            // 4. 同时修改 Nacos 原生权重 (兼容 Nacos 控制台显示)
            targetInstance.weight = weight

            // 5. 提交更新 (Nacos SDK 会发送 PUT 请求更新实例信息)
            namingService.registerInstance(serviceId, targetInstance)

            logger.info("✅ 实例权重已更新: $serviceId [$ip:$port] -> $weight")
            "权重已更新为 $weightInt"
        }
            .subscribeOn(Schedulers.boundedElastic()) // 关键：异步执行
            .map { msg -> StandardApiResponse.success(msg) }
    }

}