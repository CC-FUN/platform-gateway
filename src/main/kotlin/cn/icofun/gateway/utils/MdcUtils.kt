package cn.icofun.gateway.utils

import org.slf4j.MDC

/**
 * MDC（Mapped Diagnostic Context）工具类
 * 统一管理MDC的操作
 */
object MdcUtils {

    private const val TRACE_ID_KEY = "traceId"
    private const val DEFAULT_TRACE_ID = "unknown-trace-id"

    /**
     * 获取当前的TraceId
     */
    fun getTraceId(): String? {
        return MDC.get(TRACE_ID_KEY)
    }

    /**
     * 设置TraceId
     */
    fun setTraceId(traceId: String) {
        MDC.put(TRACE_ID_KEY, traceId)
    }

    /**
     * 移除TraceId
     */
    fun removeTraceId() {
        MDC.remove(TRACE_ID_KEY)
    }

    /**
     * 根据请求路径生成唯一的trace_id（路径 + 时间戳 → MD5哈希）
     */
    fun generatePathBasedTraceId(requestPath: String): String {
        val pathHash = Sha256Utils.sha256AsHex(requestPath.toByteArray())
        val timestampHash = Sha256Utils.sha256AsHex(System.currentTimeMillis().toString().toByteArray())
        return "${pathHash.take(32)}${timestampHash.take(32)}"
    }

    /**
     * 在MDC上下文中执行代码块
     * 自动管理TraceId的设置和清理
     *
     * 使用示例：
     * MdcUtils.withTraceId("trace-123") {
     *     // 在这里执行的代码都会带有traceId
     *     doSomething()
     * }
     */
    inline fun <T> withTraceId(traceId: String, block: () -> T): T {
        return try {
            setTraceId(traceId)
            block()
        } finally {
            removeTraceId()
        }
    }

    /**
     * 获取当前MDC中的所有值
     * 用于调试或日志记录
     */
    fun getAllMdcValues(): Map<String, String> {
        return MDC.getCopyOfContextMap() ?: emptyMap()
    }

    /**
     * 获取当前的TraceId，如果不存在则返回默认值
     */
    fun getTraceIdOrDefault(): String {
        return getTraceId() ?: DEFAULT_TRACE_ID
    }

    /**
     * 清空MDC中的所有值
     */
    fun clearAll() {
        MDC.clear()
    }

    /**
     * 设置通用的MDC键值对
     * @param key 键
     * @param value 值
     */
    fun put(key: String, value: String) {
        MDC.put(key, value)
    }

    /**
     * 移除指定的MDC键
     * @param key 键
     */
    fun remove(key: String) {
        MDC.remove(key)
    }
}