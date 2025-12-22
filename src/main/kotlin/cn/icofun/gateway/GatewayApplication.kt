package cn.icofun.gateway

import cn.icofun.gateway.loadbalancer.GrayLoadBalancerConfig
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients
import org.springframework.scheduling.annotation.EnableScheduling
import reactor.core.publisher.Hooks

@EnableScheduling // [修改点] 必须开启定时任务支持
@SpringBootApplication
@LoadBalancerClients(defaultConfiguration = [GrayLoadBalancerConfig::class])
class GatewayApplication

fun main(args: Array<String>) {
    // 开启 Reactor 调试模式（可选，方便排查异步堆栈丢失问题）
    Hooks.enableAutomaticContextPropagation()
    Hooks.onOperatorDebug()
    runApplication<GatewayApplication>(*args)
}