package cn.icofun.gateway.init

import cn.icofun.gateway.model.entity.GatewayRouteEntity
import cn.icofun.gateway.repository.GatewayRouteRepository
import cn.icofun.gateway.route.RedisRouteDefinitionRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.cloud.gateway.filter.FilterDefinition
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition
import org.springframework.cloud.gateway.route.RouteDefinition
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.net.URI

@Component
class RouteInitRunner(
    private val gatewayRouteRepository: GatewayRouteRepository,
    private val redisRouteDefinitionRepository: RedisRouteDefinitionRepository,
    private val objectMapper: ObjectMapper
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override fun run(args: ApplicationArguments?) {
        logger.info("Starting to load routes from database to Redis...")

        gatewayRouteRepository.findAll()
            .filter { it.enabled }
            .flatMap { entity ->
                try {
                    val definition = convertToRouteDefinition(entity)
                    redisRouteDefinitionRepository.save(Mono.just(definition))
                        .doOnSuccess { logger.info("路由加载成功: ${definition.id}") }
                } catch (e: Exception) {
                    logger.error("Failed to convert/load route: ${entity.id}", e)
                    Mono.empty()
                }
            }
            .doOnError { e -> logger.error("Critical error during route initialization", e) }
            .subscribe()
    }

    private fun convertToRouteDefinition(entity: GatewayRouteEntity): RouteDefinition {
        val definition = RouteDefinition()
        definition.id = entity.id
        definition.uri = URI.create(entity.uri)
        definition.order = entity.orderNum

        // 解析 Predicates JSON
        if (entity.predicates.isNotBlank()) {
            val predicates: List<PredicateDefinition> = objectMapper.readValue(entity.predicates)
            definition.predicates = predicates
        }

        // 解析 Filters JSON
        if (!entity.filters.isNullOrBlank()) {
            val filters: List<FilterDefinition> = objectMapper.readValue(entity.filters)
            definition.filters = filters
        }

        // 解析 Metadata
        if (!entity.metadata.isNullOrBlank()) {
            val metadata: Map<String, Any> = objectMapper.readValue(entity.metadata)
            definition.metadata = metadata
        }

        return definition
    }
}