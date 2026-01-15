package cn.icofun.gateway.runtime.traffic.loadbalancer.task

import cn.icofun.gateway.runtime.traffic.loadbalancer.HealthCheckManager
import org.slf4j.LoggerFactory
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration

@Component
class HealthCheckTask(
    private val healthCheckManager: HealthCheckManager,
    private val discoveryClient: ReactiveDiscoveryClient,
    webClientBuilder: WebClient.Builder
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val webClient = webClientBuilder.build()

    // 每 10 秒执行一次全量检查
    @Scheduled(fixedRate = 10000)
    fun runHealthCheck() {
        discoveryClient.services
            .filter { it != "platform-gateway" }
            .flatMap { serviceId ->
                discoveryClient.getInstances(serviceId)
            }
            .parallel()
            .runOn(Schedulers.boundedElastic())
            .flatMap { instance ->
                val instanceId = "${instance.host}:${instance.port}"
                val url = "http://$instanceId/actuator/health"

                webClient.get()
                    .uri(url)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(2))
                    .map {
                        healthCheckManager.markHealthy(instanceId)
                        log.debug("✅ Instance $instanceId is healthy")
                        instanceId
                    }
                    .onErrorResume { e ->
                        // 记录警告日志并执行隔离逻辑
                        log.warn("❌ Instance $instanceId is unhealthy [${e.message}], isolating...")
                        healthCheckManager.markUnhealthy(instanceId)
                        // 显式指定泛型，确保响应式链条不中断
                        Mono.empty<String>()
                    }
            }
            .sequential()
            .subscribe()
    }
}