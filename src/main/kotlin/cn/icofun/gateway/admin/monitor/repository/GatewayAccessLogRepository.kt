package cn.icofun.gateway.admin.monitor.repository

import cn.icofun.gateway.admin.monitor.model.entity.GatewayAccessLogEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.r2dbc.repository.R2dbcRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.LocalDateTime

interface GatewayAccessLogRepository : R2dbcRepository<GatewayAccessLogEntity, Long> {

    // 复杂查询建议使用 R2dbcEntityTemplate 或 Criteria，这里为了演示使用 @Query (简单场景)
    // 注意：R2DBC 的动态 SQL 比较麻烦，这里我们先提供一个最近 100 条的查询，
    // 或者你可以直接用 findAll(Pageable) 如果数据量不大的话。
    // 在生产环境，通常建议对接 Elasticsearch。

    fun findByRequestTimeBetween(start: LocalDateTime, end: LocalDateTime, page: Pageable): Flux<GatewayAccessLogEntity>

    fun countByRequestTimeBetween(start: LocalDateTime, end: LocalDateTime): Mono<Long>
}