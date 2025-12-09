package cn.icofun.gateway.utils

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKey

@Component
class JwtUtils {
    @Value("\${jwt.secret:thisIsAVeryLongSecretKeyForJwtSecurityTesting2025}")
    private lateinit var secret: String

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
}