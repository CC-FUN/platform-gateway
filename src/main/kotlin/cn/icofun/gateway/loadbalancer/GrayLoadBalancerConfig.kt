package cn.icofun.gateway.loadbalancer

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
        loadBalancerClientFactory: LoadBalancerClientFactory
    ): ReactorLoadBalancer<ServiceInstance> {
        val name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME) ?: "default"
        return GrayLoadBalancer(
            loadBalancerClientFactory.getLazyProvider(name, ServiceInstanceListSupplier::class.java),
            name
        )

    }
}