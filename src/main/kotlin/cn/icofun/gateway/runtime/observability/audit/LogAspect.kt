package cn.icofun.gateway.runtime.observability.audit

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Aspect
@Component
class LogAspect {
    private val logger = LoggerFactory.getLogger(this::class.java)

    @Around("@annotation(cn.icofun.gateway.runtime.observability.audit.LogOperation)")
    fun around(joinPoint: ProceedingJoinPoint): Any? {
        val signature = joinPoint.signature as MethodSignature
        val annotation = signature.method.getAnnotation(LogOperation::class.java)

        val start = System.currentTimeMillis()

        // 关键点 1: 必须克隆一份参数数组，或者在进入响应式链之前将其转换为 String
        // 这样可以避免 joinPoint 对象的生命周期在异步链中结束导致的数据丢失或 NPE
        val paramString = formatArgsSafe(joinPoint.args)

        val result = try {
            joinPoint.proceed()
        } catch (e: Throwable) {
            logOperation(annotation, paramString, System.currentTimeMillis() - start, false, e.message)
            throw e
        }

        if (result is Mono<*>) {
            return result.doOnSuccess {
                logOperation(annotation, paramString, System.currentTimeMillis() - start, true)
            }.doOnError {
                logOperation(annotation, paramString, System.currentTimeMillis() - start, false, it.message)
            }
        }

        if (result is Flux<*>) {
            return result.doOnComplete {
                logOperation(annotation, paramString, System.currentTimeMillis() - start, true)
            }.doOnError {
                logOperation(annotation, paramString, System.currentTimeMillis() - start, false, it.message)
            }
        }

        logOperation(annotation, paramString, System.currentTimeMillis() - start, true)
        return result
    }

    /**
     * 关键点 2: 使用最安全的方式处理参数数组，完全避开 Kotlin 的 Lambda 非空断言
     */
    private fun formatArgsSafe(args: Array<Any?>?): String {
        if (args.isNullOrEmpty()) return "None"

        val sb = StringBuilder()
        for (i in args.indices) {
            val arg: Any? = args[i]
            // 使用 String.valueOf(Object) 处理，如果是 null 会返回字符串 "null"
            sb.append(java.lang.String.valueOf(arg))
            if (i < args.size - 1) {
                sb.append(", ")
            }
        }
        return sb.toString()
    }

    private fun logOperation(
        annotation: LogOperation,
        params: String, // 直接接收处理好的字符串
        duration: Long,
        success: Boolean,
        errorMsg: String? = null
    ) {
        logger.info(
            "📝 AUDIT LOG | Module: {} | Op: {} | Duration: {}ms | Result: {} | Params: [{}] | Error: {}",
            annotation.module,
            annotation.description,
            duration,
            if (success) "Success" else "Failure",
            params,
            errorMsg ?: "None"
        )
    }
}