package cn.icofun.gateway.admin.rule.security.repository

import cn.icofun.gateway.admin.rule.security.entity.IpBlacklistEntity
import org.springframework.data.r2dbc.repository.R2dbcRepository

interface IpBlacklistRepository : R2dbcRepository<IpBlacklistEntity, String> {}