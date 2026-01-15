package cn.icofun.gateway.admin.route.service

import cn.icofun.gateway.infra.exception.BusinessException
import cn.icofun.gateway.admin.route.init.GatewayRouteConverter
import cn.icofun.gateway.admin.route.model.dto.CustomFilterDTO
import cn.icofun.gateway.admin.route.model.dto.CustomPredicateDTO
import cn.icofun.gateway.admin.route.model.dto.GatewayRouteDTO
import cn.icofun.gateway.admin.monitor.model.entity.GatewayConfigHistoryEntity
import cn.icofun.gateway.admin.route.model.entity.GatewayRouteEntity
import cn.icofun.gateway.admin.monitor.repository.GatewayConfigHistoryRepository
import cn.icofun.gateway.admin.route.repository.GatewayRouteRepository
import cn.icofun.gateway.admin.route.support.RedisRouteDefinitionRepository
import cn.icofun.gateway.infra.event.CacheRefreshPublisher
import cn.icofun.gateway.infra.utils.SecurityUtils
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.event.RefreshRoutesEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.net.URI
import java.time.Duration

@Service
class DynamicRouteService(
    private val redisRepository: RedisRouteDefinitionRepository,
    private val routeRepository: GatewayRouteRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val objectMapper: ObjectMapper,
    private val cacheRefreshPublisher: CacheRefreshPublisher,
    private val historyRepository: GatewayConfigHistoryRepository,
    private val transactionalOperator: TransactionalOperator,
    private val gatewayRouteConverter: GatewayRouteConverter
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val CONFIG_TYPE = "ROUTE"
    private val ROUTE_REFRESH_TOPIC = "gateway:route:refresh"

    @PostConstruct
    fun initRoutes() {
        logger.info("开始加载网关动态路由...")
        Mono.delay(Duration.ofMillis(500))
            .thenMany(routeRepository.findAll())
            .flatMap { entity ->
                val definition = gatewayRouteConverter.convert(entity)
                redisRepository.save(Mono.just(definition))
            }.doOnComplete {
                logger.info("网关动态路由加载完成，正在发布刷新事件...")
                publishAndBroadcastRoutes().subscribe()
            }
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                {},
                { error -> logger.error("❌ 动态路由初始化失败", error) } // onError
            )
    }

    fun getAll(): Flux<GatewayRouteDTO> {
        return routeRepository.findAll()
            .map { entity -> convertEntityToDto(entity) }
    }

    fun save(routeDto: GatewayRouteDTO): Mono<Void> {
        validateRoute(routeDto)
        val entity = convertDtoToEntity(routeDto)

        // 封装核心保存逻辑，便于重试
        fun doSaveLogic(): Mono<Void> {
            return routeRepository.findById(entity.id)
                .flatMap { existing ->
                    // 检查是否有实际变更
                    if (existing.uri == entity.uri &&
                        existing.predicates == entity.predicates &&
                        existing.filters == entity.filters &&
                        existing.metadata == entity.metadata &&
                        existing.orderNum == entity.orderNum &&
                        existing.enabled == entity.enabled &&
                        existing.description == entity.description
                    ) {
                        logger.info("路由配置无实际变化，跳过审计与数据库写入: ${entity.id}")
                        return@flatMap Mono.just(existing)
                    }

                    // 记录更新前快照 -> 更新 -> 记录更新后快照 -> 发布
                    recordHistory(existing.id, "Update Route (Before)", existing)
                        .then(routeRepository.save(entity.copy(id = existing.id).apply { markNotNew() }))
                        .flatMap { saved ->
                            recordHistory(saved.id, "Update Route (After)", saved)
                                .then(publishAndBroadcastRoutes())
                        }

                }.switchIfEmpty(
                    // 记录不存在 -> 插入 -> 记录快照 -> 发布
                    routeRepository.save(entity.apply { markNew() })
                        .flatMap { saved ->
                            recordHistory(saved.id, "Create Route (After)", saved)
                                .then(publishAndBroadcastRoutes())
                        }
                        .onErrorResume(DataIntegrityViolationException::class.java) {
                            // 并发插入冲突，重新查询并执行更新逻辑
                            logger.warn("插入路由时检测到主键冲突，转为更新逻辑: ${entity.id}")
                            routeRepository.findById(entity.id)
                                .flatMap { existing ->
                                    recordHistory(existing.id, "Update Route (Before)", existing)
                                        .then(
                                            routeRepository.save(
                                                entity.copy(id = existing.id).apply { markNotNew() })
                                        )
                                        .flatMap { saved ->
                                            recordHistory(saved.id, "Update Route (After)", saved)
                                                .then(publishAndBroadcastRoutes())
                                        }
                                }
                        }
                )
                .then()
        }

        // 执行保存，并捕获并发导致的主键冲突异常
        return doSaveLogic()
            .`as`(transactionalOperator::transactional)
            .onErrorResume(DataIntegrityViolationException::class.java) {
                logger.warn("检测到并发路由保存冲突 (DataIntegrityViolation)，尝试重新执行以触发更新逻辑: ${entity.id}")
                // 再次调用 doSave()，此时 findById 应该能查到刚才并发插入的记录，从而进入 update 分支
                doSaveLogic().`as`(transactionalOperator::transactional)
            }
    }

    fun delete(id: String): Mono<Void> {
        return routeRepository.findById(id)
            .switchIfEmpty(Mono.error(BusinessException(404, "route.not.found", args = arrayOf(id))))
            .flatMap { existing ->
                recordHistory(existing.id, "Delete Route", existing)
                    .then(routeRepository.deleteById(id))
            }
            .then(
                redisRepository.delete(Mono.just(id)).onErrorResume { Mono.empty() }
            )
            .then(publishAndBroadcastRoutes())
    }

    // 【新增】回滚
    @Transactional
    fun rollback(historyId: Long): Mono<GatewayRouteEntity> {
        return historyRepository.findById(historyId)
            .switchIfEmpty(Mono.error(BusinessException(404, "sys.config.history.not_found")))
            .flatMap { history ->
                val entity = objectMapper.readValue(history.snapshot, GatewayRouteEntity::class.java)
                routeRepository.save(entity.apply { markNotNew() })
                    .flatMap { saved ->
                        recordHistory(saved.id, "Rollback to ver ${history.id}", saved)
                            .then(publishAndBroadcastRoutes())
                            .thenReturn(saved)
                    }
            }
    }

    // 【新增】查询历史
    fun getHistory(routeId: String): Flux<GatewayConfigHistoryEntity> {
        return historyRepository.findByConfigTypeAndConfigId(CONFIG_TYPE, routeId)
            .sort(Comparator.comparing(GatewayConfigHistoryEntity::createTime).reversed())
    }

    private fun recordHistory(id: String, desc: String, entity: GatewayRouteEntity): Mono<GatewayConfigHistoryEntity> {
        val snapshot = objectMapper.writeValueAsString(entity)
        val operator = SecurityUtils.getCurrentUsername().defaultIfEmpty("SYSTEM")
        return operator.flatMap { user ->
            historyRepository.save(
                GatewayConfigHistoryEntity(
                    configType = CONFIG_TYPE, configId = id, snapshot = snapshot, operator = user, description = desc
                )
            )
        }
    }

    private fun publishAndBroadcastRoutes(): Mono<Void> {
        return Mono.fromRunnable<Void> {
            logger.info("📢 正准备发布路由刷新事件 RefreshRoutesEvent...")
            eventPublisher.publishEvent(RefreshRoutesEvent(this))
        }
            .subscribeOn(Schedulers.boundedElastic())
            .then(cacheRefreshPublisher.publishRefresh(ROUTE_REFRESH_TOPIC).then())
    }

    fun publishRoutes(): Mono<Void> {
        logger.info("开始执行路由发布(同步 DB -> Redis)...")
        return routeRepository.findAll()
            .filter { it.enabled }
            .flatMap { entity ->
                val definition = gatewayRouteConverter.convert(entity)
                redisRepository.save(Mono.just(definition))
            }
            .collectList()
            .flatMap {
                Mono.fromRunnable<Void> {
                    logger.info("✅ Redis同步完成，发布 RefreshRoutesEvent")
                    eventPublisher.publishEvent(RefreshRoutesEvent(this))
                }.subscribeOn(Schedulers.boundedElastic())
            }
    }

    private fun validateRoute(dto: GatewayRouteDTO) {
        if (dto.id.isBlank()) throw BusinessException(400, "route.id.missing")
        try {
            URI.create(dto.uri)
        } catch (_: Exception) {
            throw BusinessException(400, "route.uri.invalid", args = arrayOf(dto.uri))
        }
        if (dto.predicates.isEmpty()) {
            throw BusinessException(400, "route.predicate.missing")
        }
        dto.predicates.forEach {
            if (it.name.isBlank()) throw BusinessException(400, "route.predicate.name.missing")
        }
    }

    private fun convertDtoToEntity(dto: GatewayRouteDTO): GatewayRouteEntity {
        return GatewayRouteEntity(
            id = dto.id,
            uri = dto.uri,
            predicates = objectMapper.writeValueAsString(dto.predicates),
            filters = objectMapper.writeValueAsString(dto.filters),
            metadata = objectMapper.writeValueAsString(dto.metadata),
            orderNum = dto.order,
            enabled = dto.enabled,       // DTO 里的 enabled
            description = dto.description // DTO 里的 description
        )
    }

    private fun convertEntityToDto(entity: GatewayRouteEntity): GatewayRouteDTO {
        // 1. 解析 Predicates
        val predicates: List<CustomPredicateDTO> = try {
            if (entity.predicates.isBlank()) emptyList()
            else objectMapper.readValue(entity.predicates, object : TypeReference<List<CustomPredicateDTO>>() {})
        } catch (e: Exception) {
            logger.error("解析路由 Predicates 失败 ID: ${entity.id}", e)
            emptyList()
        }

        // 2. 解析 Filters
        val filters: List<CustomFilterDTO> = try {
            if (entity.filters.isNullOrBlank()) emptyList()
            else objectMapper.readValue(entity.filters, object : TypeReference<List<CustomFilterDTO>>() {})
        } catch (e: Exception) {
            logger.error("解析路由 Filters 失败 ID: ${entity.id}", e)
            emptyList()
        }

        // 3. 解析 Metadata
        val metadata: Map<String, Any> = try {
            if (entity.metadata.isNullOrBlank()) emptyMap()
            else objectMapper.readValue(entity.metadata, object : TypeReference<Map<String, Any>>() {})
        } catch (e: Exception) {
            logger.error("解析路由 Metadata 失败 ID: ${entity.id}", e)
            emptyMap()
        }

        return GatewayRouteDTO(
            id = entity.id,
            uri = entity.uri,
            order = entity.orderNum,
            enabled = entity.enabled,
            description = entity.description,
            predicates = predicates,
            filters = filters,
            metadata = metadata
        )
    }

    /**
     * 【新增】局部更新路由元数据 (支持 API 弃用等功能)
     */
    @Transactional
    fun updateMetadata(id: String, newMetadata: Map<String, Any?>): Mono<Void> {
        return routeRepository.findById(id)
            .switchIfEmpty(Mono.error(BusinessException(404, "route.not.found", args = arrayOf(id))))
            .flatMap { existing ->
                // 1. 解析原有的 Metadata
                val currentMeta: MutableMap<String, Any> = try {
                    if (existing.metadata.isNullOrBlank()) mutableMapOf()
                    else objectMapper.readValue(existing.metadata, object : TypeReference<Map<String, Any>>() {})
                        .toMutableMap()
                } catch (e: Exception) {
                    logger.error("解析原有路由 Metadata 失败 ID: $id", e)
                    mutableMapOf()
                }

                // 2. 合并新的元数据 (过滤掉 null 值)
                newMetadata.forEach { (key, value) ->
                    if (value == null) currentMeta.remove(key)
                    else currentMeta[key] = value
                }

                val updatedMetaJson = objectMapper.writeValueAsString(currentMeta)

                // 3. 检查是否有实际变化
                if (existing.metadata == updatedMetaJson) {
                    return@flatMap Mono.empty()
                }

                // 4. 记录审计日志并保存
                recordHistory(existing.id, "Update Route Metadata (Deprecation/Plugin)", existing)
                    .then(routeRepository.save(existing.copy(metadata = updatedMetaJson).apply { markNotNew() }))
                    .flatMap { saved ->
                        // 5. 将更新后的路由重新同步到 Redis 并通知集群刷新 L1
                        val definition = gatewayRouteConverter.convert(saved)
                        redisRepository.save(Mono.just(definition))
                            .then(publishAndBroadcastRoutes())
                    }
            }
    }
}