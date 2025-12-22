package cn.icofun.gateway.annotation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class LogOperation(
    val module: String,
    val description: String
)