package cn.icofun.gateway.admin.route.model.entity

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table

@Table("gateway_route")
@JsonIgnoreProperties(ignoreUnknown = true)
data class GatewayRouteEntity(
    @Id
    @get:JvmName("routeId")
    val id: String,
    val uri: String,
    val predicates: String,
    val filters: String?,
    val metadata: String?,
    val orderNum: Int = 0,
    val enabled: Boolean = true,
    val description: String?,
) : Persistable<String> {

    @Transient
    @JsonIgnore
    private var isNewRecord: Boolean = false

    override fun getId(): String = id
    @JsonIgnore
    override fun isNew(): Boolean = isNewRecord

    fun markNew() {
        this.isNewRecord = true
    }

    fun markNotNew() {
        this.isNewRecord = false
    }
}