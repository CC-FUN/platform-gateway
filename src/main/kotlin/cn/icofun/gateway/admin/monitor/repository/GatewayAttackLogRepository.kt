package cn.icofun.gateway.admin.monitor.repository

import cn.icofun.gateway.admin.monitor.model.entity.GatewayAttackLogEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.LocalDateTime

@Repository
interface GatewayAttackLogRepository : ReactiveCrudRepository<GatewayAttackLogEntity, Long> {

    // 基础的分页与条件查询 (R2DBC 暂不支持复杂动态 SQL，这里用原生 SQL 或简单的命名查询)
    // 这里演示基于时间的倒序查询
    @Query("""
        SELECT * FROM gateway_attack_log 
        WHERE (:attackType IS NULL OR attack_type = :attackType)
        AND (:clientIp IS NULL OR client_ip = :clientIp)
        AND attack_time BETWEEN :startTime AND :endTime
        ORDER BY attack_time DESC
        LIMIT :limit OFFSET :offset
    """)
    fun findByCondition(
        attackType: String?,
        clientIp: String?,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
        limit: Int,
        offset: Long
    ): Flux<GatewayAttackLogEntity>

    @Query("""
        SELECT COUNT(*) FROM gateway_attack_log 
        WHERE (:attackType IS NULL OR attack_type = :attackType)
        AND (:clientIp IS NULL OR client_ip = :clientIp)
        AND attack_time BETWEEN :startTime AND :endTime
    """)
    fun countByCondition(
        attackType: String?,
        clientIp: String?,
        startTime: LocalDateTime,
        endTime: LocalDateTime
    ): Mono<Long>

    fun findFirstByOrderByAttackTimeDesc(): Mono<GatewayAttackLogEntity>
}