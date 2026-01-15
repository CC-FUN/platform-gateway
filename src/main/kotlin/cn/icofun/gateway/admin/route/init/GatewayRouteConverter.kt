package cn.icofun.gateway.admin.route.init

import cn.icofun.gateway.admin.route.model.dto.CustomFilterDTO
import cn.icofun.gateway.admin.route.model.dto.CustomPredicateDTO
import cn.icofun.gateway.admin.route.model.entity.GatewayRouteEntity
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.cloud.gateway.filter.FilterDefinition
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition
import org.springframework.cloud.gateway.route.RouteDefinition
import org.springframework.stereotype.Component
import java.net.URI

@Component
class GatewayRouteConverter(
    private val objectMapper: ObjectMapper
) {

     fun convert(entity: GatewayRouteEntity): RouteDefinition {
        val definition = RouteDefinition()
//        val definition = RouteDefinition().apply {
//            id = entity.id
//            uri = URI.create(entity.uri)
//            order = entity.orderNum
//        }

        definition.setId(entity.id)
        definition.setUri(URI.create(entity.uri))
        definition.setOrder(entity.orderNum)


        try {
            // 1. 解析 Predicates (使用 CustomPredicateDTO 接收，解决 List -> String 类型不匹配问题)
            if (entity.predicates.isNotBlank()) {
                val customPredicates: List<CustomPredicateDTO> = objectMapper.readValue(
                    entity.predicates, object : TypeReference<List<CustomPredicateDTO>>() {}
                )

                val predicateDefinitions = customPredicates.map { cp ->
                    val pd = PredicateDefinition()
                    pd.setName(cp.name)
                    cp.args.forEach { (k, v) ->
                        val value = if (v is List<*>) v.joinToString(",") else v.toString()
                        pd.addArg(k, value)
                    }
                    pd
                }
                definition.predicates.addAll(predicateDefinitions)
            }

            // 2. 解析 Filters (同样使用 CustomFilterDTO 接收，保持逻辑一致性)
            if (!entity.filters.isNullOrBlank()) {
                val customFilters: List<CustomFilterDTO> = objectMapper.readValue(
                    entity.filters, object : TypeReference<List<CustomFilterDTO>>() {}
                )
                val filterDefinitions = customFilters.map { cf ->
                    val fd = FilterDefinition()
                    fd.setName(cf.name)
                    cf.args.forEach { (k, v) ->
                        fd.addArg(k, v.toString())
                    }
                    fd
                }
                // 修复点：使用 addAll 代替直接赋值
                definition.filters.addAll(filterDefinitions)
            }

            // 3. 解析 Metadata
            if (!entity.metadata.isNullOrBlank()) {
                val metadata: Map<String, Any> = objectMapper.readValue(
                    entity.metadata, object : TypeReference<Map<String, Any>>() {}
                )
                definition.metadata.putAll(metadata)
            }
        } catch (e: Exception) {
            // 抛出异常以便上层捕获并打印具体的路由ID
            throw RuntimeException("解析路由定义 JSON 失败", e)
        }

        return definition
    }

}