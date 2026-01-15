package cn.icofun.gateway.infra.test

data class MockHttpRequest(
    val uri: String,                    // 模拟请求路径, e.g. "/api/user/1"
    val method: String = "GET",         // 模拟请求方法
    val headers: Map<String, String> = emptyMap(),
    val queryParams: Map<String, String> = emptyMap(),
    val remoteIp: String = "127.0.0.1"
)