package cn.icofun.gateway.admin.monitor.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono

@Service
class GrafanaSyncService(
    // 1. 注入 Builder
    webClientBuilder: WebClient.Builder,
    // 2. 直接在构造器注入配置 (更安全，便于测试)
    @param:Value("\${grafana.url}") private val grafanaUrl: String,
    @param:Value("\${grafana.username}") private val adminUser: String,
    @param:Value("\${grafana.password}") private val adminPass: String
){
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val webClient: WebClient = webClientBuilder
        .baseUrl(grafanaUrl)
        // 可以在这里统一设置默认 Header
        .defaultHeaders { headers ->
            headers.setBasicAuth(adminUser, adminPass)
        }
        .build()

    fun createGrafanaUser(username: String, email: String, password: String): Mono<Void> {
        if (password.isBlank()) {
            return Mono.empty()
        }

        val body = mapOf(
            "name" to username,
            "email" to email.ifBlank { "$username@gateway.local" }, // 防止 Email 为空 Grafana 报错
            "login" to username,
            "password" to password
        )
        return webClient
            .post()
            .uri("/api/admin/users")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono<String>()
            .doOnSuccess {
                logger.info("Grafana 用户同步成功: $username ($email)")
            }
            .onErrorResume { e ->
                logger.error("Grafana 用户同步失败 (已忽略): ${e.message}")
                Mono.empty()
            }
            .then()
    }
}