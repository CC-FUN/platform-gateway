package cn.icofun.gateway.infra.config

import cn.icofun.gateway.infra.i18n.DbMessageSource
import cn.icofun.gateway.infra.i18n.LocaleUtils
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

@Configuration
class I18nConfig(
    private val dbMessageSource: DbMessageSource
) {

    private val logger: Logger = LoggerFactory.getLogger(this::class.java)

    @Bean
    fun messageSource(): MessageSource {
        logger.info("Configuring MessageSource...")
        val fileSource = ReloadableResourceBundleMessageSource()
        fileSource.setBasenames("classpath:i18n/common")
        fileSource.setDefaultEncoding("UTF-8")
        fileSource.setCacheSeconds(3600)

        dbMessageSource.parentMessageSource = fileSource
        return dbMessageSource
    }

    /**
     * 配置LocaleChangeInterceptor：从请求参数切换语言（如?lang=zh_CN）
     */
    @Bean
    fun localeResolver(): LocaleContextResolver {
        return object : LocaleContextResolver {
            override fun resolveLocaleContext(exchange: ServerWebExchange): LocaleContext {
                val locale = LocaleUtils.getValidLocale(exchange, arrayOf("common"))
                return SimpleLocaleContext(locale)
            }

            override fun setLocaleContext(exchange: ServerWebExchange, localeContext: LocaleContext?) {

            }
        }
    }
}