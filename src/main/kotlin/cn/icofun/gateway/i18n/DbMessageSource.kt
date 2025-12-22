package cn.icofun.gateway.i18n

import cn.icofun.gateway.repository.SysI18nMessageRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.support.AbstractMessageSource
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.text.MessageFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 数据库动态国际化消息源
 * 优先从内存缓存读取，缓存由数据库加载
 */
@Component("dbMessageSource")
class DbMessageSource(
    private val repository: SysI18nMessageRepository
) : AbstractMessageSource() {

    private val log = LoggerFactory.getLogger(this::class.java)

    private var cache = ConcurrentHashMap<Locale, MutableMap<String, String>>()

    /**
     * 应用启动时初始化加载
     */
    @PostConstruct
    fun init() {
        reloadMono().subscribe()
    }

    /**
     * 重新加载数据库中的消息到内存
     * (当在后台修改了文案后，调用此方法刷新)
     */
    fun reloadMono(): Mono<List<*>> {
        log.info("🔄 开始加载动态国际化消息...")
        return repository.findAll()
            .collectList()
            .map { list ->
                val newCache = ConcurrentHashMap<Locale, MutableMap<String, String>>()

                // 2. 重新构建缓存
                list.forEach { entity ->
                    val locale = parseLocale(entity.locale)
                    val map = newCache.computeIfAbsent(locale) { mutableMapOf() }

                    val fullKey = if (entity.module == "common") {
                        entity.msgKey
                    } else {
                        "${entity.module}:${entity.msgKey}"
                    }

                    map[fullKey] = entity.message
                }
                this.cache = newCache
                log.info("✅ 动态国际化消息加载完成，共 ${list.size} 条")

                list
            }
    }

    /**
     * 核心解析逻辑：Spring 调用此方法获取消息
     */
    override fun resolveCode(code: String, locale: Locale): MessageFormat? {
        // 1. 尝试精确匹配 Locale (例如 zh_CN)
        var msg = cache[locale]?.get(code)

        // 2. 如果没找到，尝试按语言匹配 (例如 zh)
        if (msg == null) {
            val languageLocale = Locale.Builder().setLanguage(locale.language).build()
            msg = cache[languageLocale]?.get(code)
        }

        if (msg == null && code.contains(":")) {
            val rawKey = code.substringAfter(":")
            msg = cache[locale]?.get(rawKey)
        }

        // 3. 返回格式化对象，如果为 null，Spring 会自动去 parentMessageSource (文件) 查找
        return if (msg != null) {
            createMessageFormat(msg, locale)
        } else {
            null
        }
    }

    /**
     * 辅助方法：解析 "zh_CN" 字符串为 Locale 对象
     */
    private fun parseLocale(localeStr: String): Locale {
        return Locale.forLanguageTag(localeStr.replace("_", "-"))
    }
}