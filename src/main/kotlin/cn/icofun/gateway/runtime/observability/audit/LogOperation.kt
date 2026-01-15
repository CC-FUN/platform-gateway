package cn.icofun.gateway.runtime.observability.audit

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class LogOperation(
    val module: String,
    val description: String
)