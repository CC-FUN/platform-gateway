package cn.icofun.gateway.infra.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl
import org.springdoc.core.properties.SwaggerUiConfigProperties
import org.springframework.cloud.gateway.event.RefreshRoutesEvent
import org.springframework.cloud.gateway.route.RouteDefinitionRepository
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration
@Primary
class RedisSwaggerConfig(
    private val routeDefinitionRepository: RouteDefinitionRepository,
    private val swaggerUiConfigProperties: SwaggerUiConfigProperties,
) : ApplicationListener<RefreshRoutesEvent> {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val API_URI = "/v3/api-docs"

    @PostConstruct
    fun init() {
        refreshSwaggerUrls()
    }

    override fun onApplicationEvent(event: RefreshRoutesEvent) {
        refreshSwaggerUrls()
    }

    private fun refreshSwaggerUrls() {
        routeDefinitionRepository.routeDefinitions
            .collectList()
            .subscribe({ routes ->
                val swaggerUrls = mutableListOf<SwaggerUrl>()
                routes.forEach { route ->
                    if (route.uri?.scheme == "lb") {
                        val serviceName = route.uri?.host
                        val pathPredicate = route.predicates.find { it.name == "Path" }

                        if (pathPredicate != null && serviceName != null) {
                            val pattern = pathPredicate.args["pattern"]
                                ?: pathPredicate.args["_genkey_0"]
                                ?: ""
                            val prefix = pattern.replace("/**", "").replace("/*", "")

                            if (prefix.isNotEmpty()) {
                                val fullUrl = prefix + API_URI

                                val swaggerUrl = SwaggerUrl().apply {
                                    name = serviceName
                                    url = fullUrl
                                    displayName = serviceName
                                }
                                swaggerUrls.add(swaggerUrl)
                            }
                        }
                    }
                }
                swaggerUiConfigProperties.urls = swaggerUrls.toSet()
                logger.info("🚀 [RedisSwaggerConfig] Auto-updated Swagger URLs: {}", swaggerUrls.map { it.url })
            }, { error ->
                logger.error("❌ [RedisSwaggerConfig] Failed to update Swagger URLs", error)
            })
    }
}