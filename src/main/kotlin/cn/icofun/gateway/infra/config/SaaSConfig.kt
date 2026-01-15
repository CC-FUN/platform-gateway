package cn.icofun.gateway.infra.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "saas")
class SaaSConfig {

    var ingressUrl: String = "http://localhost:8081"
}