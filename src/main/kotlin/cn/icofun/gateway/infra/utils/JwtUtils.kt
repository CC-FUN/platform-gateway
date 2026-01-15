package cn.icofun.gateway.infra.utils

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtParser
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.nio.charset.StandardCharsets
import java.util.*
import javax.crypto.SecretKey

@Component
class JwtUtils {
    @Value("\${jwt.secret:thisIsAVeryLongSecretKeyForJwtSecurityTesting2025}")
    private lateinit var secret: String

    @Value("\${jwt.expiration:86400000}")
    private var expiration: Long = 86400000

    private lateinit var cachedKey: SecretKey
    private lateinit var cachedParser: JwtParser

    @PostConstruct
    fun init() {
        // 初始化一次，全生命周期复用
        this.cachedKey = Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
        this.cachedParser = Jwts.parser()
            .verifyWith(cachedKey)
            .build()
    }

    fun parseTokenMono(token: String): Mono<Claims> {
        return Mono.fromCallable {
            val resolvedToken = resolveToken(token)
            cachedParser.parseSignedClaims(resolvedToken).payload
        }
            .subscribeOn(Schedulers.parallel())
    }

    fun validateToken(token: String): Mono<Boolean> {
        return parseTokenMono(token)
            .map { true }
            .onErrorResume { _ ->
                Mono.just(false)
            }
    }

    fun generateToken(username: String, expire: Long): String {
        val now = Date()
        val expiryDate = Date(now.time + expire)

        return Jwts.builder()
            .subject(username)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(cachedKey)
            .compact()
    }

    fun getUsername(token: String): Mono<String> {
        return parseTokenMono(token)
            .map { it.subject }
    }

    private fun resolveToken(header: String): String {
        return if (header.startsWith("Bearer ")) {
            header.substring(7).trim()
        } else {
            header.trim()
        }
    }
}