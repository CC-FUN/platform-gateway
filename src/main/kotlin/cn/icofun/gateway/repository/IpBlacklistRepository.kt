package cn.icofun.gateway.repository

import cn.icofun.gateway.model.entity.IpBlacklistEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository

interface IpBlacklistRepository : R2dbcRepository<IpBlacklistEntity, String> {}