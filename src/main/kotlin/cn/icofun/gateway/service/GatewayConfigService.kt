package cn.icofun.gateway.service

import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono

@Service
class GatewayConfigService(
    private val redisTemplate: ReactiveStringRedisTemplate
) {

    companion object {
        const val KEY_JWT_WHITELIST = "gateway:config:whitelist:jwt"
        const val KEY_SIGN_WHITELIST = "gateway:config:whitelist:sign"
        const val KEY_APP_SECRETS = "gateway:config:app-secrets"
        const val KEY_IP_BLACKLIST = "gateway:config:blacklist:ip"
    }

    fun getJwtWhiteList(): Mono<List<String>> {
        return redisTemplate.opsForSet().members(KEY_JWT_WHITELIST).collectList()
    }

    fun addJwtWhiteList(path: String): Mono<Long> {
        return redisTemplate.opsForSet().add(KEY_JWT_WHITELIST, path)
    }

    fun removeJwtWhiteList(path: String): Mono<Long> {
        return redisTemplate.opsForSet().remove(KEY_JWT_WHITELIST, path)
    }

    fun getSignWhitelist(): Mono<List<String>> {
        return redisTemplate.opsForSet().members(KEY_SIGN_WHITELIST).collectList()
    }

    fun addSignWhitelist(path: String): Mono<Long> {
        return redisTemplate.opsForSet().add(KEY_SIGN_WHITELIST, path)
    }

    fun removeSignWhitelist(path: String): Mono<Long> {
        return redisTemplate.opsForSet().remove(KEY_SIGN_WHITELIST, path)
    }

    fun getAppSecret(appId: String): Mono<String> {
        return redisTemplate.opsForHash<String, String>().get(KEY_APP_SECRETS, appId)
    }

    fun getAllAppSecrets(): Mono<Map<String, String>> {
        return redisTemplate.opsForHash<String, String>().entries(KEY_APP_SECRETS)
            .collectMap({ it.key }, { it.value })
    }

    fun addAppSecret(appId: String, secret: String): Mono<Boolean> {
        return redisTemplate.opsForHash<String, String>().put(KEY_APP_SECRETS, appId, secret)
    }

    fun removeAppSecret(appId: String): Mono<Long> {
        return redisTemplate.opsForHash<String, String>().remove(KEY_APP_SECRETS, appId)
    }

    fun getIpBlacklist(): Mono<List<String>> {
        return redisTemplate.opsForSet().members(KEY_IP_BLACKLIST).collectList()
    }

    fun addIpBlacklist(ip: String): Mono<Long> {
        return redisTemplate.opsForSet().add(KEY_IP_BLACKLIST, ip)
    }

    fun removeIpBlacklist(ip: String): Mono<Long> {
        return redisTemplate.opsForSet().remove(KEY_IP_BLACKLIST, ip)
    }

    fun isIpBlacklisted(ip: String): Mono<Boolean> {
        return redisTemplate.opsForSet().isMember(KEY_IP_BLACKLIST, ip)
    }
}