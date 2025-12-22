package cn.icofun.gateway.controller

import cn.icofun.gateway.annotation.LogOperation
import cn.icofun.gateway.i18n.I18nMessageUtils
import cn.icofun.gateway.model.StandardApiResponse
import cn.icofun.gateway.model.entity.GatewayAppSecretEntity
import cn.icofun.gateway.model.entity.GatewayConfigHistoryEntity
import cn.icofun.gateway.model.entity.GatewayMaskFieldEntity
import cn.icofun.gateway.model.entity.GatewayProjectionFieldEntity
import cn.icofun.gateway.model.entity.GatewayWhitelistEntity
import cn.icofun.gateway.model.entity.IpBlacklistEntity
import cn.icofun.gateway.service.GatewayConfigService
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/actuator/gateway/config")
class ConfigController(
    private val configService: GatewayConfigService,
    private val i18nMessageUtils: I18nMessageUtils
) {
    @GetMapping("/whitelist/jwt")
    fun getJwtWhiteList(): Mono<StandardApiResponse<List<GatewayWhitelistEntity>>> {
        return configService.getJwtWhiteList()
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "config.whitelist", description = "Add JWT Whitelist")
    @PostMapping("/whitelist/jwt")
    fun addJwtWhiteList(
        @RequestParam path: String,
        @RequestParam(required = false) remark: String?,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return configService.addJwtWhiteList(path, remark)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("config.jwt.add.success", arrayOf(path), exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            }
    }

    @LogOperation(module = "config.whitelist", description = "Remove JWT Whitelist")
    @DeleteMapping("/whitelist/jwt")
    fun removeJwtWhiteList(
        @RequestParam path: String,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return configService.removeJwtWhiteList(path)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("config.jwt.remove.success", arrayOf(path), exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            }
    }


    @GetMapping("/whitelist/sign")
    fun getSignWhiteList(): Mono<StandardApiResponse<List<GatewayWhitelistEntity>>> {
        return configService.getSignWhitelist()
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "config.whitelist", description = "Add Sign Whitelist")
    @PostMapping("/whitelist/sign")
    fun addSignWhiteList(
        @RequestParam path: String,
        @RequestParam(required = false) remark: String?,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return configService.addSignWhitelist(path, remark)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("config.sign.add.success", arrayOf(path), exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            }
    }

    @LogOperation(module = "config.whitelist", description = "Remove Sign Whitelist")
    @DeleteMapping("/whitelist/sign")
    fun removeSignWhiteList(
        @RequestParam path: String,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return configService.removeSignWhitelist(path)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("config.sign.remove.success", arrayOf(path), exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            }
    }

    @GetMapping("/app-secrets")
    fun getAllSecrets(): Mono<StandardApiResponse<List<GatewayAppSecretEntity>>> {
        return configService.getAllAppSecrets()
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "config.secret", description = "Add App Secret")
    @PostMapping("/app-secrets")
    fun addAppSecret(
        @RequestParam appId: String,
        @RequestParam secret: String,
        @RequestParam(required = false) remark: String?,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return configService.addAppSecret(appId, secret, remark)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("config.secret.add.success", arrayOf(appId), exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            }
    }

    @LogOperation(module = "config.secret", description = "Remove App Secret")
    @DeleteMapping("/app-secrets/{appId}")
    fun removeAppSecret(
        @PathVariable appId: String,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return configService.removeAppSecret(appId)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("config.secret.remove.success", arrayOf(appId), exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            }
    }

    @GetMapping("/blacklist/ip")
    fun getIpBlacklist(): Mono<StandardApiResponse<List<IpBlacklistEntity>>> {
        return configService.getIpBlacklist()
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "config.ip", description = "Add IP Blacklist")
    @PostMapping("/blacklist/ip")
    fun addIpBlacklist(
        @RequestParam ip: String,
        @RequestParam(required = false) remark: String?,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return configService.addIpBlacklist(ip, remark)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("config.ip.add.success", arrayOf(ip), exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            }
    }

    @LogOperation(module = "config.ip", description = "Remove IP Blacklist")
    @DeleteMapping("/blacklist/ip")
    fun removeIpBlacklist(
        @RequestParam ip: String,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return configService.removeIpBlacklist(ip)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("config.ip.remove.success", arrayOf(ip), exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            }
    }

    @GetMapping("/mask-fields")
    fun getMaskFields(): Mono<StandardApiResponse<List<GatewayMaskFieldEntity>>> {
        return configService.getMaskFields()
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "config.mask", description = "Add Mask Field")
    @PostMapping("/mask-fields")
    fun addMaskField(
        @RequestParam field: String,
        @RequestParam(required = false) remark: String?,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return configService.addMaskField(field, remark)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("config.mask.add.success", arrayOf(field), exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            }
    }

    @LogOperation(module = "config.mask", description = "Remove Mask Field")
    @DeleteMapping("/mask-fields")
    fun removeMaskField(
        @RequestParam field: String,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return configService.removeMaskField(field)
            .flatMap {
                val msg = i18nMessageUtils.getMessage("config.mask.remove.success", arrayOf(field), exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            }
    }

    @GetMapping("/history/{configType}/{configId}")
    fun getConfigHistory(
        @PathVariable configType: String,
        @PathVariable configId: String
    ): Mono<StandardApiResponse<List<GatewayConfigHistoryEntity>>> {
        return configService.getHistory(configType, configId)
            .collectList()
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "config.projection", description = "Query Projection Fields")
    @GetMapping("/projection-fields")
    fun getProjectionFields(): Mono<StandardApiResponse<List<GatewayProjectionFieldEntity>>> {
        return configService.getProjectionFields() // 返回 List<GatewayProjectionFieldEntity>
            .map { StandardApiResponse.success(it) }
    }

    @LogOperation(module = "config.projection", description = "Add Projection Field")
    @PostMapping("/projection-fields")
    fun addProjectionField(
        @RequestParam field: String,
        @RequestParam(required = false) remark: String?,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return configService.addProjectionField(field, remark) // 内部处理 DB、Redis 和集群 L1 缓存
            .flatMap {
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            }
    }

    @LogOperation(module = "config.projection", description = "Remove Projection Field")
    @DeleteMapping("/projection-fields")
    fun removeProjectionField(
        @RequestParam field: String,
        exchange: ServerWebExchange
    ): Mono<StandardApiResponse<String>> {
        return configService.removeProjectionField(field) // 联动清理集群缓存
            .flatMap {
                val msg = i18nMessageUtils.getMessage("sys.op.success", null, exchange.request)
                Mono.just(StandardApiResponse.success(msg))
            }
    }
}