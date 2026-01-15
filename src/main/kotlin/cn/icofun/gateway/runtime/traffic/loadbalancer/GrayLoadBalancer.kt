package cn.icofun.gateway.runtime.traffic.loadbalancer

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.cloud.client.ServiceInstance
import org.springframework.cloud.client.loadbalancer.*
import org.springframework.cloud.loadbalancer.core.NoopServiceInstanceListSupplier
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier
import org.springframework.http.HttpHeaders
import reactor.core.publisher.Mono
import java.util.concurrent.ThreadLocalRandom

/**
 * 灰度发布负载均衡器
 * 逻辑：
 * 1. 从请求头获取 "Gray-Version"
 * 2. 如果有值（例如 v2），则只在 Nacos 元数据 version=v2 的实例中挑一个。
 * 3. 如果没值，则在所有实例中轮询（或者你可以改为只访问 v1 稳定版）。
 */
class GrayLoadBalancer(
    private val serviceInstanceListSupplierProvider: ObjectProvider<ServiceInstanceListSupplier>,
    private val serviceId: String,
    private val healthCheckManager: HealthCheckManager
) : ReactorServiceInstanceLoadBalancer {

    private val logger = LoggerFactory.getLogger(this::class.java)
    private val GRAY_HEADER = "Gray-Version" // 定义灰度请求头

    override fun choose(request: Request<*>): Mono<Response<ServiceInstance>> {
        val supplier = serviceInstanceListSupplierProvider.getIfAvailable { NoopServiceInstanceListSupplier() }
        return supplier.get(request).next().map { serviceInstances ->
            processInstanceSelection(serviceInstances, request)
        }
    }

    private fun processInstanceSelection(
        instances: List<ServiceInstance>,
        request: Request<*>?
    ): Response<ServiceInstance> {
        if (instances.isEmpty()) {
            logger.warn("[GrayLB] Service [$serviceId] has NO instances available!")
            return EmptyResponse()
        }

        // 1. 基础健康检查过滤 (结合本地黑名单)
        val healthyInstances = instances.filter {
            healthCheckManager.isHealthy("${it.host}:${it.port}")
        }

        // 1. 基础健康检查过滤
        val availableInstances = healthyInstances.ifEmpty { instances }

        // 2. 粘性灰度特征提取 (Header)
        val clientRequest = request?.context as? RequestDataContext
        val headers = clientRequest?.clientRequest?.headers ?: HttpHeaders.EMPTY
        val grayVersion = headers.getFirst(GRAY_HEADER)

        // 3. 版本筛选：如果 Header 指定了版本，只在对应版本里挑
        val candidates = if (!grayVersion.isNullOrBlank()) {
            val matched = availableInstances.filter {
                grayVersion.equals(it.metadata?.get("version"), ignoreCase = true)
            }
            matched.ifEmpty { availableInstances }
        } else {
            availableInstances
        }

        // 4. 执行加权随机算法
        return DefaultResponse(selectByWeight(candidates))
    }

    private fun selectByWeight(instances: List<ServiceInstance>): ServiceInstance {
        if (instances.size == 1) return instances[0]

        // 计算总权重 (默认权重设为 100)
        val totalWeight = instances.sumOf { instance ->
            val weightStr = instance.metadata?.get("weight")
            val weight = weightStr?.toIntOrNull() ?: 100
            logger.debug(
                "[GrayLB] {} Instance {}:{} weight: {} -> {}",
                serviceId,
                instance.host,
                instance.port,
                weightStr,
                weight
            )

            weight
        }

        // 如果权重设置异常或均为0，降级为完全随机
        if (totalWeight <= 0) {
            logger.warn("[GrayLB] {} Total weight is {}, using random selection", serviceId, totalWeight)
            return instances[ThreadLocalRandom.current().nextInt(instances.size)]
        }

        logger.debug("[GrayLB] {} Total weight: {}, instance count: {}", serviceId, totalWeight, instances.size)

        // 核心：加权随机算法
        var randomPos = ThreadLocalRandom.current().nextInt(totalWeight)
        for (instance in instances) {
            val weightStr = instance.metadata?.get("weight")
            val weight = weightStr?.toIntOrNull() ?: 100
            randomPos -= weight
            if (randomPos < 0) {
                logger.debug("[GrayLB] {} Selected instance: {}:{}", serviceId, instance.host, instance.port)
                return instance
            }
        }
        return instances[0]
    }
}