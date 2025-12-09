package cn.icofun.gateway.config

import cn.icofun.gateway.i18n.LocaleUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.i18n.LocaleContext
import org.springframework.context.i18n.SimpleLocaleContext
import org.springframework.context.support.ReloadableResourceBundleMessageSource
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.i18n.LocaleContextResolver
import java.util.*

@Configuration
class I18nConfig {

    companion object {
        private const val I18N_BASE_PATH = "classpath:i18n/"
        private val MODULES = arrayOf("common")
    }

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Bean
    fun messageSource(): MessageSource {
        logger.info("开始配置 MessageSource...")
        val source = ReloadableResourceBundleMessageSource()
        val baseNames = discoverAllI18nBaseNames()
        source.setBasenames(*baseNames)
        source.setDefaultEncoding("UTF-8")
        source.setCacheSeconds(if (isDevEnvironment()) 0 else 3600)

        logger.info("=== I18n配置加载完成 ===")
        baseNames.forEach { logger.debug("注册基名: $it") }
        return source
    }

    /**
     * 发现模块下的消息类型文件
     */
    fun discoverAllI18nBaseNames(): Array<String> {
        val baseNames = mutableListOf<String>()
        logger.info("开始扫描所有模块的资源文件...")
        for (module in MODULES) {
            val moduleDir = "$I18N_BASE_PATH$module"
            logger.debug("扫描模块目录: $moduleDir")
            baseNames.add(moduleDir)
            logger.debug("✅ 注册模块目录基名: $moduleDir")
        }
        logger.info("所有模块资源扫描完成，共注册 ${baseNames.size} 个基名")
        return baseNames.toTypedArray()
    }

    /**
     * 判断是否为开发环境（根据Spring Profile）
     */
    private fun isDevEnvironment(): Boolean {
        val isActive = System.getProperty("spring.profiles.active")?.contains("dev") == true
        if (isActive) logger.debug("当前环境: 开发") else logger.debug("当前环境: 生产")
        return isActive
    }

    /**
     * 配置LocaleChangeInterceptor：从请求参数切换语言（如?lang=zh_CN）
     */
    @Bean
    fun localeResolver(): LocaleContextResolver {
        return object : LocaleContextResolver {
            override fun resolveLocaleContext(exchange: ServerWebExchange): LocaleContext {
                val locale = LocaleUtils.getValidLocale(exchange, MODULES)
                return SimpleLocaleContext(locale)
            }

            override fun setLocaleContext(exchange: ServerWebExchange, localeContext: LocaleContext?) {

            }
        }
    }
}