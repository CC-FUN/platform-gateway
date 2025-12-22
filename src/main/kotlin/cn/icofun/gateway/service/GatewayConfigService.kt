package cn.icofun.gateway.service

import cn.icofun.gateway.exception.BusinessException
import cn.icofun.gateway.model.entity.*
import cn.icofun.gateway.repository.*
import cn.icofun.gateway.utils.SecurityUtils
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit

@Service
class GatewayConfigService(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val ipBlacklistRepository: IpBlacklistRepository,
    private val whitelistRepository: GatewayWhitelistRepository,
    private val appSecretRepository: GatewayAppSecretRepository,
    private val maskFieldRepository: GatewayMaskFieldRepository,
    private val historyRepository: GatewayConfigHistoryRepository,
    private val projectionFieldRepository: GatewayProjectionFieldRepository,
    private val cacheRefreshPublisher: CacheRefreshPublisher
) {

    private val logger = LoggerFactory.getLogger(this::class.java)

    companion object {
        const val KEY_JWT_WHITELIST = "gateway:config:whitelist:jwt"
        const val KEY_SIGN_WHITELIST = "gateway:config:whitelist:sign"
        const val KEY_APP_SECRETS = "gateway:config:app-secrets"
        const val KEY_IP_BLACKLIST = "gateway:config:blacklist:ip"
        const val KEY_DATA_MASK_FIELDS = "gateway:config:mask_fields"
        const val KEY_PROJECTION_FIELDS = "gateway:config:projection_fields"

        const val TYPE_JWT = "JWT"
        const val TYPE_SIGN = "SIGN"
        const val TYPE_WHITELIST = "GLOBAL_WHITELIST"
        const val TYPE_APP_SECRET = "APP_SECRET"
        const val TYPE_IP_BLACKLIST = "IP_BLACKLIST"
        const val TYPE_MASK_FIELD = "MASK_FIELD"
        const val TYPE_PROJECTION_FIELD = "PROJECTION_FIELD"

        const val EVENT_IP_CHANGE = "CONFIG:IP_BLACKLIST"
        const val EVENT_JWT_CHANGE = "CONFIG:JWT_WHITELIST"
        const val EVENT_SIGN_CHANGE = "CONFIG:SIGN_WHITELIST"
        const val EVENT_SECRET_CHANGE = "CONFIG:APP_SECRET"
        const val EVENT_MASK_CHANGE = "CONFIG:MASK_FIELD"
        const val EVENT_PROJECTION_CHANGE = "CONFIG:PROJECTION_FIELD"
        const val EVENT_LIMIT_CHANGE = "CONFIG:LIMIT_RULE"


    }

    // ================= L1 本地缓存定义 (Caffeine) =================

    // 缓存 IP 黑名单集合 (Key固定为 "ALL_IPS")
    private val ipBlacklistCache: Cache<String, Set<String>> = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS) // 本地缓存1小时兜底
        .maximumSize(10) // 只有1个key，给点余量
        .build()

    // 缓存 JWT 白名单集合 (Key固定为 "ALL_JWT_PATHS")
    private val jwtWhitelistCache: Cache<String, List<String>> = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build()

    // 缓存 签名 白名单集合 (Key固定为 "ALL_SIGN_PATHS")
    private val signWhitelistCache: Cache<String, List<String>> = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build()

    // 缓存 脱敏字段集合 (Key固定为 "ALL_MASK_FIELDS")
    private val maskFieldCache: Cache<String, List<String>> = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build()

    // 缓存 App Secret (Key为 AppId)
    private val appSecretCache: Cache<String, String> = Caffeine.newBuilder()
        .expireAfterWrite(30, TimeUnit.MINUTES) // 密钥有效期稍短，确保安全性
        .maximumSize(1000)
        .build()

    private val projectionFieldCache: Cache<String, List<String>> = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build()

    private val limitRuleCache: Cache<String, Int> = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .maximumSize(10000)
        .build()

    // =============================================================

    @PostConstruct
    fun initCache() {
        logger.info("开始预热网关配置缓存...")

        ipBlacklistRepository.findAll().map { it.ip }
            .collectList().filter { it.isNotEmpty() }
            .flatMap { redisTemplate.opsForSet().add(KEY_IP_BLACKLIST, *it.toTypedArray()) }
            .subscribe()

        whitelistRepository.findByType(TYPE_JWT).map { it.path }
            .collectList().filter { it.isNotEmpty() }
            .flatMap { redisTemplate.opsForSet().add(KEY_JWT_WHITELIST, *it.toTypedArray()) }
            .subscribe()

        whitelistRepository.findByType(TYPE_SIGN).map { it.path }
            .collectList().filter { it.isNotEmpty() }
            .flatMap { redisTemplate.opsForSet().add(KEY_SIGN_WHITELIST, *it.toTypedArray()) }
            .subscribe()

        appSecretRepository.findAll()
            .collectMap({ it.appId }, { it.appSecret })
            .filter { it.isNotEmpty() }
            .flatMap { redisTemplate.opsForHash<String, String>().putAll(KEY_APP_SECRETS, it) }
            .subscribe()

        maskFieldRepository.findAll().map { it.fieldName }
            .collectList().filter { it.isNotEmpty() }
            .flatMap { redisTemplate.opsForSet().add(KEY_DATA_MASK_FIELDS, *it.toTypedArray()) }
            .subscribe()

        projectionFieldRepository.findAll().map { it.fieldName }
            .collectList().filter { it.isNotEmpty() }
            .flatMap { redisTemplate.opsForSet().add(KEY_PROJECTION_FIELDS, *it.toTypedArray()) }
            .subscribe()

        logger.info("网关配置缓存预热完成")
    }
    // ==================== 空 ====================

    fun getProjectionFields(): Mono<List<GatewayProjectionFieldEntity>> {
        return projectionFieldRepository.findAll().collectList()
    }

    fun getProjectionFieldsFromCache(): Mono<List<String>> {
        val cached = projectionFieldCache.getIfPresent("ALL_PROJECTION_FIELDS")
        if (cached != null) return Mono.just(cached)

        return redisTemplate.opsForSet().members(KEY_PROJECTION_FIELDS).collectList()
            .doOnNext { projectionFieldCache.put("ALL_PROJECTION_FIELDS", it) }
    }

    fun addProjectionField(field: String, remark: String?): Mono<Void> {
        if (field.isBlank()) return Mono.error(BusinessException(400, "config.projection_field.missing"))

        val record = recordAudit(TYPE_PROJECTION_FIELD, field, "Add Projection Field: $field")
        val entity = GatewayProjectionFieldEntity(fieldName = field, remark = remark)

        return record.then(projectionFieldRepository.save(entity))
            .flatMap { redisTemplate.opsForSet().add(KEY_PROJECTION_FIELDS, field) }
            .flatMap { cacheRefreshPublisher.publishRefresh(EVENT_PROJECTION_CHANGE) }
            .then()
    }

    fun removeProjectionField(field: String): Mono<Void> {
        val record = recordAudit(TYPE_PROJECTION_FIELD, field, "Delete Projection Field: $field")
        return record.then(projectionFieldRepository.deleteById(field))
            .then(redisTemplate.opsForSet().remove(KEY_PROJECTION_FIELDS, field))
            .then(cacheRefreshPublisher.publishRefresh(EVENT_PROJECTION_CHANGE))
            .then()
    }

    // ... initCache 等原有代码

    /**
     * 【新增】获取限流阈值 (L1 -> Redis)
     */
    fun getLimitThreshold(type: String, value: String): Mono<Int> {
        val cacheKey = "$type:$value"
        val cached = limitRuleCache.getIfPresent(cacheKey)
        if (cached != null) {
            return Mono.just(cached)
        }

        // Redis Key 格式需与 Controller 写入的一致
        val redisKey = "gateway:ratelimit:config:$type:$value"
        return redisTemplate.opsForValue().get(redisKey)
            .map { it.toInt() }
            .doOnNext { threshold ->
                // 放入本地缓存
                limitRuleCache.put(cacheKey, threshold)
            }
    }


    // ==================== JWT Whitelist ====================

    fun getJwtWhiteList(): Mono<List<GatewayWhitelistEntity>> {
        return whitelistRepository.findByType(TYPE_JWT).collectList()
    }

    fun getJwtWhitelistFromCache(): Mono<List<String>> {
        // 1. 优先查 L1
        val cached = jwtWhitelistCache.getIfPresent("ALL_JWT_PATHS")
        if (cached != null) return Mono.just(cached)

        // 2. 查 L2 (Redis) 并回填 L1
        return redisTemplate.opsForSet().members(KEY_JWT_WHITELIST).collectList()
            .doOnNext { list ->
                logger.debug("Load JWT Whitelist from Redis -> L1 Cache")
                jwtWhitelistCache.put("ALL_JWT_PATHS", list)
            }
    }

    fun addJwtWhiteList(path: String, remark: String?): Mono<Void> {
        val record = recordAudit(TYPE_WHITELIST, path, "Add JWT Whitelist: $path")

        if (path.isBlank()) return Mono.error(BusinessException(400, "config.whitelist.path.missing"))
        val entity = GatewayWhitelistEntity(path = path, type = TYPE_JWT, remark = remark)

        return record.then(whitelistRepository.save(entity))
            .flatMap { redisTemplate.opsForSet().add(KEY_JWT_WHITELIST, path) }
            // 【新增】发布刷新事件，通知集群清理本地缓存
            .flatMap { cacheRefreshPublisher.publishRefresh(EVENT_JWT_CHANGE) }
            .then()
    }

    fun removeJwtWhiteList(path: String): Mono<Void> {
        val record = recordAudit(TYPE_WHITELIST, path, "Delete JWT Whitelist: $path")

        if (path.isBlank()) return Mono.error(BusinessException(400, "config.whitelist.path.missing")) // [校验]

        return record.then(whitelistRepository.deleteByTypeAndPath(TYPE_JWT, path))
            .then(redisTemplate.opsForSet().remove(KEY_JWT_WHITELIST, path))
            .then(cacheRefreshPublisher.publishRefresh(EVENT_JWT_CHANGE))
            .then()
    }

    // ==================== Sign Whitelist ====================

    fun getSignWhitelist(): Mono<List<GatewayWhitelistEntity>> {
        return whitelistRepository.findByType(TYPE_SIGN).collectList()
    }

    fun getSignWhitelistFromCache(): Mono<List<String>> {
        // 1. 优先查 L1
        val cached = signWhitelistCache.getIfPresent("ALL_SIGN_PATHS")
        if (cached != null) return Mono.just(cached)

        // 2. 查 L2 (Redis) 并回填 L1
        return redisTemplate.opsForSet().members(KEY_SIGN_WHITELIST).collectList()
            .doOnNext { list ->
                logger.debug("Load Sign Whitelist from Redis -> L1 Cache")
                signWhitelistCache.put("ALL_SIGN_PATHS", list)
            }
    }

    fun addSignWhitelist(path: String, remark: String?): Mono<Void> {
        val record = recordAudit(TYPE_WHITELIST, path, "Add SIGN Whitelist: $path")

        if (path.isBlank()) return Mono.error(BusinessException(400, "config.whitelist.path.missing")) // [校验]
        val entity = GatewayWhitelistEntity(path = path, type = TYPE_SIGN, remark = remark)
        return record.then(whitelistRepository.save(entity))
            .flatMap { redisTemplate.opsForSet().add(KEY_SIGN_WHITELIST, path) }
            .flatMap { cacheRefreshPublisher.publishRefresh(EVENT_SIGN_CHANGE) }
            .then()
    }

    fun removeSignWhitelist(path: String): Mono<Void> {
        val record = recordAudit(TYPE_WHITELIST, path, "Delete SIGN Whitelist: $path")

        if (path.isBlank()) return Mono.error(BusinessException(400, "config.whitelist.path.missing")) // [校验]

        return record.then(whitelistRepository.deleteByTypeAndPath(TYPE_SIGN, path))
            .then(redisTemplate.opsForSet().remove(KEY_SIGN_WHITELIST, path))
            .then(cacheRefreshPublisher.publishRefresh(EVENT_SIGN_CHANGE))
            .then()
    }

    // ==================== App Secret ====================

    fun getAppSecret(appId: String): Mono<String> {
        // 1. 优先查 L1
        val cached = appSecretCache.getIfPresent(appId)
        if (cached != null) return Mono.just(cached)

        // 2. 查 L2 (Redis) 并回填 L1
        return redisTemplate.opsForHash<String, String>().get(KEY_APP_SECRETS, appId)
            .doOnNext { secret -> appSecretCache.put(appId, secret) }
    }

    fun getAllAppSecrets(): Mono<List<GatewayAppSecretEntity>> {
        return appSecretRepository.findAll().collectList()
    }

    fun addAppSecret(appId: String, secret: String, remark: String? = null): Mono<Boolean> {
        val record = recordAudit(TYPE_APP_SECRET, appId, "Add/Update Secret for $appId")

        if (appId.isBlank() || secret.isBlank()) {
            return Mono.error(BusinessException(400, "config.appsecret.param.missing")) // [校验]
        }

        val entity = GatewayAppSecretEntity(appId = appId, appSecret = secret, remark = remark)

        return record.then(appSecretRepository.save(entity))
            .flatMap { redisTemplate.opsForHash<String, String>().put(KEY_APP_SECRETS, appId, secret) }
            .flatMap { cacheRefreshPublisher.publishRefresh(EVENT_SECRET_CHANGE).thenReturn(it) }
    }

    fun removeAppSecret(appId: String): Mono<Void> {
        val record = recordAudit(TYPE_APP_SECRET, appId, "Delete Secret for $appId")
        if (appId.isBlank()) return Mono.empty()

        return record.then(appSecretRepository.deleteById(appId))
            .then(redisTemplate.opsForHash<String, String>().remove(KEY_APP_SECRETS, appId))
            .then(cacheRefreshPublisher.publishRefresh(EVENT_SECRET_CHANGE))
            .then()
    }

    // ==================== IP Blacklist (Core High Frequency) ====================

    fun getIpBlacklist(): Mono<List<IpBlacklistEntity>> {
        return ipBlacklistRepository.findAll().collectList()
    }

    fun addIpBlacklist(ip: String, remark: String?): Mono<Long> {
        val record = recordAudit(TYPE_IP_BLACKLIST, ip, "Block IP: $ip")

        if (ip.isBlank()) return Mono.error(BusinessException(400, "config.ip.missing"))
        return record.then(
            ipBlacklistRepository.save(IpBlacklistEntity(ip = ip, remark = remark))
                .flatMap { redisTemplate.opsForSet().add(KEY_IP_BLACKLIST, ip) })
            .flatMap { cacheRefreshPublisher.publishRefresh(EVENT_IP_CHANGE).thenReturn(it) }
    }

    fun removeIpBlacklist(ip: String): Mono<Void> {
        val record = recordAudit(TYPE_IP_BLACKLIST, ip, "DesBlocked IP: $ip")

        if (ip.isBlank()) return Mono.empty()

        return record.then(ipBlacklistRepository.deleteById(ip))
            .then(redisTemplate.opsForSet().remove(KEY_IP_BLACKLIST, ip))
            .then(cacheRefreshPublisher.publishRefresh(EVENT_IP_CHANGE))
            .then()
    }

    /**
     * 判断 IP 是否在黑名单 (L1 -> Redis)
     * 优化点：不再每次请求都查 Redis 的 isMember，而是查本地 Set
     */
    fun isIpBlacklisted(ip: String): Mono<Boolean> {
        // 1. 查 L1 Set
        val localSet = ipBlacklistCache.getIfPresent("ALL_IPS")
        if (localSet != null) {
            return Mono.just(localSet.contains(ip))
        }

        // 2. 查 L2 Redis 全量数据 (对于 IP 黑名单通常数量级在可控范围，如 1~5万)
        return redisTemplate.opsForSet().members(KEY_IP_BLACKLIST).collectList()
            .map { list -> list.toSet() }
            .doOnNext { set ->
                logger.debug("Load IP Blacklist from Redis -> L1 Cache (Size: ${set.size})")
                ipBlacklistCache.put("ALL_IPS", set)
            }
            .map { set -> set.contains(ip) }
            .defaultIfEmpty(false)
    }

    // ==================== Mask Fields ====================

    fun getMaskFields(): Mono<List<GatewayMaskFieldEntity>> {
        return maskFieldRepository.findAll().collectList()
    }

    fun getMaskFieldNamesFromCache(): Mono<List<String>> {
        // 1. 查 L1
        val cached = maskFieldCache.getIfPresent("ALL_MASK_FIELDS")
        if (cached != null) return Mono.just(cached)

        // 2. 查 Redis
        return redisTemplate.opsForSet().members(KEY_DATA_MASK_FIELDS).collectList()
            .doOnNext { list -> maskFieldCache.put("ALL_MASK_FIELDS", list) }
    }

    fun addMaskField(field: String, remark: String?): Mono<Long> {
        val record = recordAudit(TYPE_MASK_FIELD, field, "Add Mask: $field")

        if (field.isBlank()) return Mono.error(BusinessException(400, "config.maskfield.missing")) // [校验]

        return record.then(maskFieldRepository.save(GatewayMaskFieldEntity(fieldName = field, remark = remark)))
            .flatMap { redisTemplate.opsForSet().add(KEY_DATA_MASK_FIELDS, field) }
            .flatMap { cacheRefreshPublisher.publishRefresh(EVENT_MASK_CHANGE).thenReturn(it) }
    }

    fun removeMaskField(field: String): Mono<Void> {
        val record = recordAudit(TYPE_MASK_FIELD, field, "Delete Mask: $field")

        if (field.isBlank()) return Mono.empty()
        return record.then(maskFieldRepository.deleteById(field))
            .then(redisTemplate.opsForSet().remove(KEY_DATA_MASK_FIELDS, field))
            .then(cacheRefreshPublisher.publishRefresh(EVENT_MASK_CHANGE))
            .then()
    }

    // ==================== 辅助方法 ====================

    /**
     * 【新增】供 RedisConfig 监听器调用的本地缓存清理方法
     */
    fun clearLocalCache(eventType: String) {
        when (eventType) {
            EVENT_IP_CHANGE -> {
                logger.info("🧹 [L1 Cache] 检测到变更，清除 IP 黑名单本地缓存")
                ipBlacklistCache.invalidateAll()
            }

            EVENT_JWT_CHANGE -> {
                logger.info("🧹 [L1 Cache] 检测到变更，清除 JWT 白名单本地缓存")
                jwtWhitelistCache.invalidateAll()
            }

            EVENT_SIGN_CHANGE -> {
                logger.info("🧹 [L1 Cache] 检测到变更，清除 签名 白名单本地缓存")
                signWhitelistCache.invalidateAll()
            }

            EVENT_SECRET_CHANGE -> {
                logger.info("🧹 [L1 Cache] 检测到变更，清除 AppSecret 本地缓存")
                appSecretCache.invalidateAll()
            }

            EVENT_MASK_CHANGE -> {
                logger.info("🧹 [L1 Cache] 检测到变更，清除 脱敏字段 本地缓存")
                maskFieldCache.invalidateAll()
            }

            EVENT_PROJECTION_CHANGE -> {
                logger.info("🧹 [L1 Cache] 清除投影字段本地缓存")
                projectionFieldCache.invalidateAll()
            }

            EVENT_LIMIT_CHANGE -> {
                logger.info("🧹 [L1 Cache] 检测到变更，清除 限流规则 本地缓存")
                limitRuleCache.invalidateAll()
            }

            else -> {
                // 兜底：如果是 "CONFIG:ALL" 或未知类型
                if (eventType.startsWith("CONFIG:")) {
                    logger.warn("⚠️ 收到未知配置刷新事件 [$eventType]，执行全量清理")
                    ipBlacklistCache.invalidateAll()
                    jwtWhitelistCache.invalidateAll()
                    signWhitelistCache.invalidateAll()
                    appSecretCache.invalidateAll()
                    maskFieldCache.invalidateAll()
                }
            }
        }
    }

    private fun recordAudit(configType: String, configId: String, desc: String): Mono<GatewayConfigHistoryEntity> {
        val operator = SecurityUtils.getCurrentUsername().defaultIfEmpty("SYSTEM")
        return operator.flatMap { user ->
            historyRepository.save(
                GatewayConfigHistoryEntity(
                    configType = configType,
                    configId = configId,
                    snapshot = desc,
                    operator = user,
                    description = desc
                )
            )
        }
    }

    fun getHistory(configType: String, configId: String): Flux<GatewayConfigHistoryEntity> {
        return historyRepository.findByConfigTypeAndConfigId(configType, configId)
            .sort(Comparator.comparing(GatewayConfigHistoryEntity::createTime).reversed())
    }

}