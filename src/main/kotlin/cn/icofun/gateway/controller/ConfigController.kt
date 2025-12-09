package cn.icofun.gateway.controller

import cn.icofun.gateway.model.dto.StandardApiResponse
import cn.icofun.gateway.service.GatewayConfigService
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/actuator/gateway/config")
class ConfigController(
    private val configService: GatewayConfigService,
) {
    @GetMapping("/whitelist/jwt")
    fun getJwtWhiteList(): Mono<StandardApiResponse<List<String>>> {
        return configService.getJwtWhiteList()
            .map { StandardApiResponse.success(it) }
    }

    @PostMapping("/whitelist/jwt")
    fun addJwtWhiteList(@RequestParam path: String): Mono<StandardApiResponse<String>> {
        return configService.addJwtWhiteList(path)
            .thenReturn(StandardApiResponse.success("Added JWT Whitelist: $path"))
    }

    @DeleteMapping("/whitelist/jwt")
    fun removeJwtWhiteList(@RequestParam path: String): Mono<StandardApiResponse<String>> {
        return configService.removeJwtWhiteList(path)
            .thenReturn(StandardApiResponse.success("Removed JWT Whitelist: $path"))
    }

    @GetMapping("/whitelist/sign")
    fun getSignWhiteList(): Mono<StandardApiResponse<List<String>>> {
        return configService.getSignWhitelist()
            .map { StandardApiResponse.success(it) }
    }

    @PostMapping("/whitelist/sign")
    fun addSignWhiteList(@RequestParam path: String): Mono<StandardApiResponse<String>> {
        return configService.addSignWhitelist(path)
            .thenReturn(StandardApiResponse.success("Added Sign Whitelist: $path"))
    }

    @DeleteMapping("/whitelist/sign")
    fun removeSignWhiteList(@RequestParam path: String): Mono<StandardApiResponse<String>> {
        return configService.removeSignWhitelist(path)
            .thenReturn(StandardApiResponse.success("Removed Sign Whitelist: $path"))
    }

    @GetMapping("/app-secrets")
    fun getAllSecrets(): Mono<StandardApiResponse<Map<String, String>>> {
        return configService.getAllAppSecrets()
            .map { StandardApiResponse.success(it) }
    }

    @PostMapping("/app-secrets")
    fun addAppSecret(@RequestParam appId: String, @RequestParam secret: String): Mono<StandardApiResponse<String>> {
        return configService.addAppSecret(appId, secret)
            .thenReturn(StandardApiResponse.success("Added App Secret for: $appId"))
    }

    @DeleteMapping("/app-secrets/{appId}")
    fun removeAppSecret(@PathVariable appId: String): Mono<StandardApiResponse<String>> {
        return configService.removeAppSecret(appId)
            .thenReturn(StandardApiResponse.success("Removed App Secret for: $appId"))
    }

    @GetMapping("/blacklist/ip")
    fun getIpBlacklist(): Mono<StandardApiResponse<List<String>>> {
        return configService.getIpBlacklist()
            .map { StandardApiResponse.success(it) }
    }

    @PostMapping("/blacklist/ip")
    fun addIpBlacklist(@RequestParam ip: String): Mono<StandardApiResponse<String>> {
        return configService.addIpBlacklist(ip)
            .thenReturn(StandardApiResponse.success("Added IP Blacklist: $ip"))
    }

    @DeleteMapping("/blacklist/ip")
    fun removeIpBlacklist(@RequestParam ip: String): Mono<StandardApiResponse<String>> {
        return configService.removeIpBlacklist(ip)
            .thenReturn(StandardApiResponse.success("Removed IP Blacklist: $ip"))
    }
}