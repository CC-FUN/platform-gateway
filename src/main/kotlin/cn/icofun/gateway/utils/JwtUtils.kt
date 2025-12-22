package cn.icofun.gateway.utils

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.*
import javax.crypto.SecretKey

@Component
class JwtUtils {
    @Value("\${jwt.secret:thisIsAVeryLongSecretKeyForJwtSecurityTesting2025}")
    private lateinit var secret: String

    @Value("\${jwt.expiration:86400000}")
    private var expiration: Long = 86400000

    private fun getSignInKey(): SecretKey? {
        return Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8))
    }

    fun parseToken(token: String): Claims {
        return Jwts.parser()
            .verifyWith(getSignInKey())
            .build()
            .parseSignedClaims(token)
            .payload
    }

    fun validateToken(token: String): Boolean {
        return try {
            parseToken(token)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun generateToken(username: String, expire: Long): String {
        val now = Date()
        val expiryDate = Date(now.time + expire)

        return Jwts.builder()
            .subject(username)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(getSignInKey())
            .compact()
    }

    fun getUsername(token: String): String {
        val token = resolveToken(token)
        return parseToken(token).subject
    }

    private fun resolveToken(header: String): String {
        return if (header.startsWith("Bearer ")) {
            header.substring(7).trim()
        } else {
            header.trim()
        }
    }
}