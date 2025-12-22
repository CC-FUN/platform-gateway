package cn.icofun.gateway.model.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

@Table("sys_i18n_message")
data class SysI18nMessageEntity(
    @Id
    val id: Long? = null,
    val module: String = "common", // [新增] 模块名
    val msgKey: String,     // 例如 "auth.login.error"
    val locale: String,     // 例如 "zh_CN", "en_US"
    val message: String,    // 例如 "用户名或密码错误"
    val createTime: LocalDateTime? = null
)