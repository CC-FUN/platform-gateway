package cn.icofun.gateway.i18n

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.server.ServerWebExchange
import java.util.*
import java.util.regex.Pattern

/**
 * Locale处理工具类
 * 统一管理语言解析、匹配、验证等逻辑
 */
object LocaleUtils {
    private val logger: Logger = LoggerFactory.getLogger(this::class.java)
    private val LANGUAGE_PATTERN = Pattern.compile("[a-z]{2}(_[A-Z]{2})?")

    /**
     * 从ServerHttpRequest中获取有效的Locale
     */
    fun getValidLocaleFromServerRequest(
        request: ServerHttpRequest,
        supportedModules: Array<String>
    ): Locale {
        val acceptLanguageHeader = request.headers[HttpHeaders.ACCEPT_LANGUAGE]
        logger.debug("Accept-Language 头: {}", acceptLanguageHeader)

        if (acceptLanguageHeader.isNullOrEmpty()) {
            logger.debug("请求头无 Accept-Language，使用默认 Locale: en")
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
        val request = exchange.request
        val acceptLanguage = request.headers.getFirst(HttpHeaders.ACCEPT_LANGUAGE)
        logger.debug("Accept-Language 头: {}", acceptLanguage)

        if (acceptLanguage.isNullOrEmpty()) {
            logger.debug("请求头无 Accept-Language，使用默认 Locale: en")
            return Locale.ENGLISH
        }

        return resolverLocale(acceptLanguage, supportedModules)
    }

    /**
     * 解析Accept-Language头并匹配最佳Locale
     */
    private fun resolverLocale(headerValue: String, supportedModules: Array<String>): Locale {
        val acceptLocales = parseAcceptedLocales(headerValue)
        logger.debug("解析后的请求语言列表: {}", acceptLocales.map { it.toLanguageTag() })

        val supportedLocales = getSupportedLocales(supportedModules)
        logger.debug("支持的语言列表: {}", supportedLocales.map { it.toLanguageTag() })

        val matchedLocale = findBestLocaleMatch(acceptLocales, supportedLocales)
        logger.debug("最终选择的 Locale: {}", matchedLocale.toLanguageTag())

        return matchedLocale
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
     * 从文件名中提取语言代码
     */
    fun extractLanguageFromFilename(module: String, filename: String): String? {
        val baseName = filename.substringBeforeLast(".")
        val langPart = baseName.removePrefix("${module}_")

        return langPart.takeIf {
            LANGUAGE_PATTERN.matcher(it).matches()
        }?.also {
            logger.info("从文件名 $filename 提取语言: $langPart")
        }
    }

    /**
     * 获取支持的语言列表
     */
    fun getSupportedLocales(modules: Array<String>): List<Locale> {
        val languages = mutableListOf<Locale>()
        val resolver = PathMatchingResourcePatternResolver()

        for (module in modules) {
            val modulePath = "classpath:i18n/"
            val pattern = "$modulePath*.properties" // 完整模式：classpath:i18n/auth/*.properties
            logger.info("🔍 解析资源模式: $pattern") // 日志：打印当前解析的路径模式
            try {
                val resources = resolver.getResources(pattern)
                logger.info("模块 $module 找到 ${resources.size} 个资源文件")

                for (resource in resources) {
                    val filename = resource.filename ?: continue
                    logger.info("处理资源文件: $filename")

                    extractLanguageFromFilename(module, filename)?.let { lang ->
                        languages.add(
                            Locale.forLanguageTag(
                                lang.replace("_", "-")
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                logger.warn("加载模块 $module 的资源文件失败: ${e.message}", e)
            }
        }
        logger.info("最终支持的语言列表: ${languages.map { it.toLanguageTag() }}")
        return languages.toList()
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