package cn.icofun.gateway.runtime.traffic.loadbalancer

import org.springframework.cloud.client.ServiceInstance
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration
class GrayLoadBalancerConfig {

    @Bean
    fun reactorServiceInstanceLoadBalancer(
        environment: Environment,
        loadBalancerClientFactory: LoadBalancerClientFactory,
        // [修改点 1] 注入 HealthCheckManager
        healthCheckManager: HealthCheckManager
    ): ReactorLoadBalancer<ServiceInstance> {
        // 从环境变量中获取当前需要负载均衡的服务名称（如: user-service）
        val name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME) ?: "default"

        return GrayLoadBalancer(
            loadBalancerClientFactory.getLazyProvider(name, ServiceInstanceListSupplier::class.java),
            name, // 传入 serviceId
            healthCheckManager // [修改点 2] 传入健康检查管理器
        )
    }
}