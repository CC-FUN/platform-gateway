package cn.icofun.gateway.service

import cn.icofun.gateway.exception.BusinessException
import cn.icofun.gateway.model.entity.GatewayConfigHistoryEntity
import cn.icofun.gateway.model.entity.SysI18nMessageEntity
import cn.icofun.gateway.repository.GatewayConfigHistoryRepository
import cn.icofun.gateway.repository.SysI18nMessageRepository
import cn.icofun.gateway.utils.SecurityUtils
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Service
class I18nMessageService(
    private val repository: SysI18nMessageRepository,
    private val cacheRefreshPublisher: CacheRefreshPublisher,
    private val historyRepository: GatewayConfigHistoryRepository,
    private val objectMapper: ObjectMapper
) {
    private val CONFIG_TYPE = "I18N_MESSAGE"

    fun findAll(): Flux<SysI18nMessageEntity> {
        return repository.findAll(Sort.by("msgKey", "locale"))
    }

    @Transactional
    fun save(entity: SysI18nMessageEntity): Mono<SysI18nMessageEntity> {
        if (entity.msgKey.isBlank() || entity.locale.isBlank()) {
            return Mono.error(BusinessException(400, "error.param.invalid")) //建议 参数错误：Key和Locale不能为空
        }

        return if (entity.id != null) {
            repository.findById(entity.id)
                .flatMap { existing ->
                    if (existing.message == entity.message && existing.module == entity.module) {
                        return@flatMap Mono.just(existing) // 无变化直接返回，不写审计也不调 DB
                    }
                    recordHistory(existing.id.toString(), "Update (Before)", existing)
                        .then(repository.save(entity.copy(id = existing.id)))
                        .flatMap { saved ->
                            // 记录更新后快照
                            recordHistory(saved.id.toString(), "Save (After)", saved)
                                .then(cacheRefreshPublisher.publishRefresh("i18n-save"))
                                .thenReturn(saved)
                        }
                }
                .switchIfEmpty(
                    // 找不到旧 ID 的极端情况，按新增处理
                    repository.save(entity).flatMap { saved ->
                        recordHistory(saved.id.toString(), "Create", saved)
                            .then(cacheRefreshPublisher.publishRefresh("i18n-save"))
                            .thenReturn(saved)
                    }
                )
        } else {
            repository.save(entity).flatMap { saved ->
                recordHistory(saved.id.toString(), "Create", saved)
                    .then(cacheRefreshPublisher.publishRefresh("i18n-save"))
                    .thenReturn(saved)
            }
        }
    }

    @Transactional
    fun delete(id: Long): Mono<Void> {
        return repository.findById(id)
            .flatMap { existing ->
                recordHistory(existing.id.toString(), "Delete", existing)
                    .then(repository.deleteById(id))
            }
            .then(cacheRefreshPublisher.publishRefresh("i18n-delete").then())
    }

    fun reloadCache(): Mono<Void> {
        return cacheRefreshPublisher.publishRefresh("i18n-admin-reload").then()
    }

    @Transactional
    fun rollback(historyId: Long): Mono<SysI18nMessageEntity> {
        return historyRepository.findById(historyId)
            .flatMap { history ->
                val entity = objectMapper.readValue(history.snapshot, SysI18nMessageEntity::class.java)
                repository.save(entity)
                    .flatMap { saved ->
                        recordHistory(saved.id.toString(), "Rollback", saved)
                            .then(cacheRefreshPublisher.publishRefresh("i18n-rollback"))
                            .thenReturn(saved)
                    }
            }
    }

    fun getHistory(id: Long): Flux<GatewayConfigHistoryEntity> {
        return historyRepository.findByConfigTypeAndConfigId(CONFIG_TYPE, id.toString())
            .sort(Comparator.comparing(GatewayConfigHistoryEntity::createTime).reversed())
    }

    @Transactional
    fun saveBatch(entities: List<SysI18nMessageEntity>): Mono<Void> {
        if (entities.isEmpty()) return Mono.empty()

        return Flux.fromIterable(entities)
            .filter { it.msgKey.isNotBlank() && it.locale.isNotBlank() }
            .flatMap { entity ->
                // 查重：根据 module + key + locale 寻找现有记录
                repository.findByMsgKeyAndLocaleAndModule(entity.msgKey, entity.locale, entity.module)
                    .flatMap { existing ->
                        // 存在则更新内容
                        repository.save(existing.copy(message = entity.message))
                    }
                    .switchIfEmpty(
                        // 不存在则直接插入
                        repository.save(entity)
                    )
            }
            .collectList()
            .flatMap { savedList ->
                val desc = "Batch Import/Update: ${savedList.size} messages processed."
                recordBatchAudit(savedList.size, desc) // 记录审计
            }
            .then(cacheRefreshPublisher.publishRefresh("i18n-batch-import"))
            .then()
    }

    /**
     * 新增：用于记录批量操作的审计快照
     */
    private fun recordBatchAudit(count: Int, desc: String): Mono<GatewayConfigHistoryEntity> {
        val operator = SecurityUtils.getCurrentUsername().defaultIfEmpty("SYSTEM")
        val snapshot = "Count: $count | $desc"
        return operator.flatMap { user ->
            historyRepository.save(
                GatewayConfigHistoryEntity(
                    // 使用一个固定的 ID 来查询所有批量操作历史
                    configType = CONFIG_TYPE,
                    configId = "BATCH_OPERATIONS",
                    snapshot = snapshot,
                    operator = user,
                    description = desc
                )
            )
        }
    }

    private fun recordHistory(
        id: String,
        desc: String,
        entity: SysI18nMessageEntity
    ): Mono<GatewayConfigHistoryEntity> {
        val snapshot = objectMapper.writeValueAsString(entity)
        val operator = SecurityUtils.getCurrentUsername().defaultIfEmpty("SYSTEM")
        return operator.flatMap { user ->
            historyRepository.save(
                GatewayConfigHistoryEntity(
                    configType = CONFIG_TYPE, configId = id, snapshot = snapshot, operator = user, description = desc
                )
            )
        }
    }
}