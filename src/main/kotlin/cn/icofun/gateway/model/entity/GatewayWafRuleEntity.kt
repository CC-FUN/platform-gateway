package cn.icofun.gateway.model.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime

/**
 * WAF 防御规则配置表
 * 用于定义 SQL注入、XSS、恶意爬虫等正则拦截规则
 */
@Table("gateway_waf_rule")
data class GatewayWafRuleEntity(
    @Id val id: Long? = null,
    val ruleName: String,       // 规则名称，如 "Block SQL Injection"
    val pattern: String,        // 正则表达式
    val matchField: String,     // 匹配域: BODY, URI, HEADER, QUERY
    val ruleType: String,       // 规则类型: SQL_INJECTION, XSS, CUSTOM
    val priority: Int = 0,      // 优先级，越大越先匹配
    val enabled: Boolean = true,// 是否启用
    val description: String? = null,
    val createTime: LocalDateTime = LocalDateTime.now()
)