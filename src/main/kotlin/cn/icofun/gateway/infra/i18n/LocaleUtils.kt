package cn.icofun.gateway.infra.i18n

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.server.ServerWebExchange
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Locale处理工具类
 * 统一管理语言解析、匹配、验证等逻辑
 */
object LocaleUtils {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val LANGUAGE_PATTERN = Pattern.compile("[a-z]{2}(_[A-Z]{2})?")

    private val supportedLocalesCache = ConcurrentHashMap<String, List<Locale>>()

    /**
     * 从ServerHttpRequest中获取有效的Locale
     */
    fun getValidLocaleFromServerRequest(
        request: ServerHttpRequest,
        supportedModules: Array<String>
    ): Locale {
        val acceptLanguageHeader = request.headers[HttpHeaders.ACCEPT_LANGUAGE]

        if (logger.isTraceEnabled) {
            logger.trace("Accept-Language: {}", acceptLanguageHeader)
        }

        if (acceptLanguageHeader.isNullOrEmpty()) {
            return Locale.ENGLISH
        }

        return resolverLocale(acceptLanguageHeader[0], supportedModules)
    }

    /**
     * 从HttpServletRequest中获取有效的Locale
     */
    fun getValidLocale(
        exchange: ServerWebExchange,
        supportedModules: Array<String>
    ): Locale {
        return getValidLocaleFromServerRequest(exchange.request, supportedModules)
    }

    /**
     * 解析Accept-Language头并匹配最佳Locale
     */
    private fun resolverLocale(headerValue: String, supportedModules: Array<String>): Locale {
        val acceptLocales = parseAcceptedLocales(headerValue)
        val supportedLocales = getSupportedLocales(supportedModules)
        return findBestLocaleMatch(acceptLocales, supportedLocales)
    }

    /**
     * 解析 Accept-Language 头中的语言标签（转换为 Locale 对象）
     */
    fun parseAcceptedLocales(header: String): List<Locale> {
        return header.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { localeStr ->
                try {
                    val languageTag = localeStr.split(";")[0].trim()
                    Locale.forLanguageTag(languageTag.replace("_", "-"))
                } catch (e: IllegalArgumentException) {
                    logger.warn("无法解析语言标签: $localeStr", e)
                    null
                }
            }
    }

    /**
     * 获取支持的语言列表
     */
    fun getSupportedLocales(modules: Array<String>): List<Locale> {
        val cacheKey = modules.sorted().joinToString (",")

        return supportedLocalesCache.computeIfAbsent(cacheKey){
            val languages = mutableListOf<Locale>()
            val resolver = PathMatchingResourcePatternResolver()

            for (module in modules) {
                val modulePath = "classpath:i18n/"
                val pattern = "$modulePath*.properties"

                logger.info("🔍 [LocaleUtils] Loading locales for module: '$module', pattern: '$pattern'")

                try {
                    val resources = resolver.getResources(pattern)
                    for (resource in resources) {
                        val filename = resource.filename ?: continue
                        logger.info("处理资源文件: $filename")
                        extractLanguageFromFilename(module, filename)?.let { lang ->
                            languages.add(Locale.forLanguageTag(lang.replace("_", "-")))
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to load locales for module $module", e)
                }
            }

            val distinctList = languages.distinct()
            logger.info("✅ [LocaleUtils] Supported locales loaded: {}", distinctList)
            distinctList

        }
    }

    private fun extractLanguageFromFilename(module: String, filename: String): String? {
        val baseName = filename.substringBeforeLast(".")
        val langPart = baseName.removePrefix("${module}_")

        return if (LANGUAGE_PATTERN.matcher(langPart).matches()) langPart else null
    }

    /**
     * 查找最佳匹配的Locale
     */
    fun findBestLocaleMatch(
        requestedLocales: List<Locale>,
        supportedLocales: List<Locale>
    ): Locale {
        requestedLocales.forEach { requested ->
            supportedLocales.firstOrNull { supported ->
                supported.language == requested.language && supported.country == requested.country
            }?.let { return it }
        }

        requestedLocales.forEach { requested ->
            supportedLocales.firstOrNull { supported ->
                supported.language == requested.language
            }?.let { return it }
        }

        return Locale.ENGLISH
    }
}