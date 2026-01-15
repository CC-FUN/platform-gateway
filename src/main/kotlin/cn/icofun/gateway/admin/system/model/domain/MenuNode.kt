package cn.icofun.gateway.admin.system.model.domain

/**
 * 菜单模型 (用于 Controller/Service 层传输)
 */
data class MenuNode(
    val id: Long? = null,
    val parentId: Long? = 0,    // 父菜单ID
    val title: String,          // 菜单标题
    val path: String,           // 路由路径
    val component: String? = null, // 前端组件路径
    val permCode: String? = null,  // 权限标识 (如 system:user:list)
    val icon: String? = null,   // 图标
    val type: Int = 1,          // 0:目录, 1:菜单, 2:按钮
    val sortOrder: Int = 0      // 排序
)