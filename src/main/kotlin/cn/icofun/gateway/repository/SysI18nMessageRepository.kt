package cn.icofun.gateway.repository

import cn.icofun.gateway.model.entity.SysI18nMessageEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Repository
interface SysI18nMessageRepository : R2dbcRepository<SysI18nMessageEntity, Long> {
    // 根据 Key 和 Locale 查找，用于判重
    fun findByMsgKeyAndLocale(msgKey: String, locale: String): Mono<SysI18nMessageEntity>
    fun findByLocale(locale: String): Flux<SysI18nMessageEntity>
    fun findByMsgKeyAndLocaleAndModule(msgKey: String, locale: String, module: String): Mono<SysI18nMessageEntity>
}