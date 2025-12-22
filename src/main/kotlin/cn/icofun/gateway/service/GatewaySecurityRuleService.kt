package cn.icofun.gateway.service

import cn.icofun.gateway.model.entity.GatewayConfigHistoryEntity
import cn.icofun.gateway.model.entity.GatewaySecurityRuleEntity
import cn.icofun.gateway.repository.GatewayConfigHistoryRepository
import cn.icofun.gateway.repository.GatewaySecurityRuleRepository
import cn.icofun.gateway.utils.SecurityUtils
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.TimeUnit

@Service
class GatewaySecurityRuleService(
    private val repository: GatewaySecurityRuleRepository,
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val cacheRefreshPublisher: CacheRefreshPublisher,
    private val historyRepository: GatewayConfigHistoryRepository
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        private const val CACHE_KEY = "gateway:security:rules"
        private const val CONFIG_TYPE = "SECURITY_RULE"

        const val EVENT_SECURITY_CHANGE = "SECURITY_CHANGE"
    }

    private val localRuleCache: Cache<String, List<GatewaySecurityRuleEntity>> = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build()

    /**
     * 系统启动时预热缓存
     */
    @PostConstruct
    fun initCache() {
        refreshRulesCache().subscribe()
    }

    /**
     * 获取所有安全规则 (L1 -> Redis L2 -> DB)
     * */
    fun getRules(): Mono<List<GatewaySecurityRuleEntity>> {
        val cachedLocal = localRuleCache.getIfPresent("ALL_RULES")
        if (cachedLocal != null) {
            return Mono.just(cachedLocal)
        }

        return redisTemplate.opsForValue().get(CACHE_KEY)
            .flatMap { json ->
                try {
                    val type = object : TypeReference<List<GatewaySecurityRuleEntity>>() {}
                    val rules = objectMapper.readValue(json, type)
                    Mono.just(rules)
                } catch (e: Exception) {
                    logger.error("解析安全规则缓存失败", e)
                    Mono.empty()
                }
            }
            .doOnNext { rules ->
                logger.debug("Load Security Rules from Redis -> L1 Cache")
                localRuleCache.put("ALL_RULES", rules)
            }
            .switchIfEmpty(
                // 3. Redis 未命中，回源 DB
                refreshRulesCache()
            )

    }

    /**
     * 从数据库加载规则并刷新到 Redis
     * (当后台管理界面修改规则后，调用此方法)
     */

    fun refreshRulesCache(): Mono<List<GatewaySecurityRuleEntity>> {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "priority")) // 按优先级降序
            .collectList()
            .flatMap { rules ->
                val json = objectMapper.writeValueAsString(rules)
                // 缓存 1 小时，防止永久不一致
                redisTemplate.opsForValue().set(CACHE_KEY, json, Duration.ofHours(24))
                    .thenReturn(rules)
            }
            .doOnSuccess { rules ->
                // 顺便刷新当前节点的 L1
                localRuleCache.put("ALL_RULES", rules)
                logger.info("🛡️ 安全规则缓存已刷新 (Count: ${rules.size})")
            }
    }

    /**
     * 【关键】供 RedisConfig 调用的清理方法
     */
    fun clearLocalCache() {
        logger.info("🧹 [L1 Cache] 清除安全规则本地缓存")
        localRuleCache.invalidateAll()
    }

    @Transactional
    fun save(entity: GatewaySecurityRuleEntity): Mono<GatewaySecurityRuleEntity> {
        return if (entity.id != null) {
            repository.findById(entity.id)
                .flatMap { existing ->
                    if (existing.path == entity.path &&
                        existing.method == entity.method &&
                        existing.type == entity.type &&
                        existing.priority == entity.priority
                    ) {
                        logger.info("安全规则内容无变化，跳过审计与数据库写入: ${entity.id}")
                        return@flatMap Mono.just(existing)
                    }

                    recordHistory(existing.id.toString(), "Update Security Rule (Before)", existing)
                        .then(repository.save(entity.copy(id = existing.id)))
                        .flatMap { saved ->
                            // 记录更新后快照，并触发缓存刷新与广播
                            recordHistory(saved.id.toString(), "Update Security Rule (After)", saved)
                                .then(refreshRulesCache())
                                .then(cacheRefreshPublisher.publishRefresh(EVENT_SECURITY_CHANGE))
                                .thenReturn(saved)
                        }
                }
                .switchIfEmpty(
                    repository.save(entity).flatMap { saved ->
                        recordHistory(saved.id.toString(), "Create Security Rule (After)", saved)
                            .then(refreshRulesCache())
                            .then(cacheRefreshPublisher.publishRefresh(EVENT_SECURITY_CHANGE))
                            .thenReturn(saved)
                    }
                )
        } else {
            repository.save(entity)
                .flatMap { saved ->
                    recordHistory(saved.id.toString(), "Create Security Rule (After)", saved)
                        .then(refreshRulesCache())
                        .then(cacheRefreshPublisher.publishRefresh(EVENT_SECURITY_CHANGE))
                        .thenReturn(saved)
                }
        }
    }

    @Transactional
    fun delete(id: Long): Mono<Void> {
        return repository.findById(id)
            .flatMap { existing ->
                // 1. 记录删除前快照
                recordHistory(existing.id.toString(), "Delete Security Rule", existing)
                    .then(repository.deleteById(id))
            }
            .then(refreshRulesCache())
            .then(cacheRefreshPublisher.publishRefresh(EVENT_SECURITY_CHANGE))
            .then()
    }

    @Transactional
    fun rollback(historyId: Long): Mono<GatewaySecurityRuleEntity> {
        return historyRepository.findById(historyId)
            .flatMap { history ->
                val entity = objectMapper.readValue(history.snapshot, GatewaySecurityRuleEntity::class.java)
                repository.save(entity)
                    .flatMap { saved ->
                        recordHistory(saved.id.toString(), "Rollback", saved)
                            .then(refreshRulesCache())
                            .then(cacheRefreshPublisher.publishRefresh(EVENT_SECURITY_CHANGE))
                            .thenReturn(saved)
                    }
            }
    }

    fun findAll(): Mono<List<GatewaySecurityRuleEntity>> {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "priority")).collectList()
    }

    fun getHistory(id: Long): Flux<GatewayConfigHistoryEntity> {
        return historyRepository.findByConfigTypeAndConfigId(CONFIG_TYPE, id.toString())
            .sort(Comparator.comparing(GatewayConfigHistoryEntity::createTime).reversed())
    }

    private fun recordHistory(
        id: String,
        desc: String,
        entity: GatewaySecurityRuleEntity
    ): Mono<GatewayConfigHistoryEntity> {
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
}