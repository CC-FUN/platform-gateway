package cn.icofun.gateway.infra.utils

import java.io.InputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

object Sha256Utils {
    private const val SHA256_ALGORITHM = "SHA-256"
    private val HEX_CHARS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')

    fun sha256AsHex(bytes: ByteArray): String {
        val digest = digest(SHA256_ALGORITHM, bytes)
        return encodeHex(digest)
    }

    fun sha256AsHex(inputStream: InputStream): String {
        val digest = digest(SHA256_ALGORITHM, inputStream)
        return encodeHex(digest)
    }

    fun appendSha256AsHex(bytes: ByteArray, builder: StringBuilder): StringBuilder {
        val hex = sha256AsHex(bytes)
        return builder.append(hex)
    }

    fun appendSha256AsHex(inputStream: InputStream, builder: StringBuilder): StringBuilder {
        val hex = sha256AsHex(inputStream)
        return builder.append(hex)
    }

    // ------------------------------ 私有辅助方法 ------------------------------
    /**
     * 获取SHA256算法的MessageDigest实例
     */
    private fun getDigest(algorithm: String): MessageDigest {
        return try {
            MessageDigest.getInstance(algorithm)
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 algorithm not available", e)
        }
    }

    /**
     * 计算摘要（字节数组或输入流）
     */
    private fun digest(algorithm: String, data: Any): ByteArray {
        val messageDigest = getDigest(algorithm)
        return when (data) {
            is ByteArray -> messageDigest.digest(data)
            is InputStream -> {
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (data.read(buffer).also { bytesRead = it } != -1) {
                    messageDigest.update(buffer, 0, bytesRead)
                }
                messageDigest.digest()
            }

            else -> throw IllegalArgumentException("Unsupported data type: ${data.javaClass.name}")
        }
    }

    /**
     * 将字节数组转十六进制字符串
     */
    private fun encodeHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val byte = bytes[i]
            chars[i * 2] = HEX_CHARS[(byte.toInt() ushr 4) and 0xF]
            chars[i * 2 + 1] = HEX_CHARS[byte.toInt() and 0xF]
        }
        return String(chars)
    }
}