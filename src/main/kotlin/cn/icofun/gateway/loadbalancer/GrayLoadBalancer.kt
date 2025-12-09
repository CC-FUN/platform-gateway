package cn.icofun.gateway.loadbalancer

import org.apache.commons.logging.LogFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.cloud.client.ServiceInstance
import org.springframework.cloud.client.loadbalancer.DefaultResponse
import org.springframework.cloud.client.loadbalancer.EmptyResponse
import org.springframework.cloud.client.loadbalancer.Request
import org.springframework.cloud.client.loadbalancer.RequestDataContext
import org.springframework.cloud.client.loadbalancer.Response
import org.springframework.cloud.loadbalancer.core.NoopServiceInstanceListSupplier
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier
import org.springframework.http.HttpHeaders
import reactor.core.publisher.Mono
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * 灰度发布负载均衡器
 * 逻辑：
 * 1. 从请求头获取 "Gray-Version"
 * 2. 如果有值（例如 v2），则只在 Nacos 元数据 version=v2 的实例中挑一个。
 * 3. 如果没值，则在所有实例中轮询（或者你可以改为只访问 v1 稳定版）。
 */
class GrayLoadBalancer(
    private val serviceInstanceListSupplierProvider: ObjectProvider<ServiceInstanceListSupplier>,
    private val serviceId: String
) : ReactorServiceInstanceLoadBalancer {
    private val log = LogFactory.getLog(this::class.java)
    private val position = AtomicInteger(ThreadLocalRandom.current().nextInt(1000))
    private val GRAY_HEADER = "Gray-Version" // 定义灰度请求头

    override fun choose(request: Request<*>?): Mono<Response<ServiceInstance>> {
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
            log.warn("No instances available for service: $serviceId")
            return EmptyResponse()
        }

        val clientRequest = request?.context as? RequestDataContext
        val headers = clientRequest?.clientRequest?.headers ?: HttpHeaders.EMPTY
        val grayVersion = headers.getFirst(GRAY_HEADER)

        val targetInstances = if (!grayVersion.isNullOrBlank()) {
            val filtered = instances.filter {
                grayVersion.equals(it.metadata["version"], ignoreCase = true)
            }
            if (filtered.isNotEmpty()) {
                log.info("🎯 [GrayRouting] 命中灰度规则: $serviceId -> version=$grayVersion, 可用实例数: ${filtered.size}")
                filtered
            } else {
                log.warn("⚠️ [GrayRouting] 指定版本 $grayVersion 无实例，降级为随机路由")
                instances
            }
        } else {
            instances
        }

        val pos = abs(position.incrementAndGet())
        val instance = targetInstances[pos % targetInstances.size]

        return DefaultResponse(instance)
    }

}