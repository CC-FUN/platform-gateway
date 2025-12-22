package cn.icofun.gateway.service

import cn.icofun.gateway.exception.BusinessException
import cn.icofun.gateway.model.entity.GatewayAccessLogEntity
import cn.icofun.gateway.repository.GatewayAccessLogRepository
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.data.relational.core.query.Query
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentLinkedQueue

@Service
class AccessLogService(
    private val logRepository: GatewayAccessLogRepository,
    private val r2dbcTemplate: R2dbcEntityTemplate
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    // 内存缓冲区：线程安全队列
    private val buffer = ConcurrentLinkedQueue<GatewayAccessLogEntity>()
    private val BATCH_SIZE = 100 // 每次批量插入的大小

    /**
     * 将日志放入缓冲区 (非阻塞)
     */
    fun saveLog(entity: GatewayAccessLogEntity) {
        buffer.offer(entity)
    }

    /**
     * 定时任务：每秒执行一次，将缓冲区数据写入数据库
     */
    @Scheduled(fixedRate = 1000)
    fun flushLogs() {
        if (buffer.isEmpty()) return

        val logsToSave = ArrayList<GatewayAccessLogEntity>()
        // 每次最多取 BATCH_SIZE 条，或者取完为止
        while (logsToSave.size < BATCH_SIZE && !buffer.isEmpty()) {
            buffer.poll()?.let { logsToSave.add(it) }
        }

        if (logsToSave.isNotEmpty()) {
            logRepository.saveAll(logsToSave)
                .subscribe(
                    {}, // onNext: ignore
                    { error -> log.error("Failed to save access logs", error) } // onError
                )
        }
    }

    @PreDestroy
    fun onDestruction() {
        flushLogs()
    }

    /**
     * 分页查询日志 (支持简单的动态条件)
     */
    fun queryLogs(
        routeId: String?,
        status: Int?,
        path: String?,
        page: Int,
        size: Int
    ): Mono<Page<GatewayAccessLogEntity>> {
        if (page < 1) {
            return Mono.error(BusinessException(400, "sys.arg.page.invalid"))
        }

        var criteria = Criteria.empty()

        if (!routeId.isNullOrBlank()) {
            criteria = criteria.and("route_id").`is`(routeId)
        }
        if (status != null) {
            criteria = criteria.and("response_status").`is`(status)
        }
        if (!path.isNullOrBlank()) {
            criteria = criteria.and("request_path").like("%$path%")
        }

        val pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "request_time"))
        val query = Query.query(criteria).with(pageable)

        return r2dbcTemplate.select(GatewayAccessLogEntity::class.java)
            .matching(query)
            .all()
            .collectList()
            .flatMap { list ->
                r2dbcTemplate.count(Query.query(criteria), GatewayAccessLogEntity::class.java)
                    .map { total -> PageImpl(list, pageable, total) }
            }
    }
}