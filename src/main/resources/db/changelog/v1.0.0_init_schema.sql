-- liquibase formatted sql

-- changeset icofun:1
-- comment: 初始化网关基础表

SET
FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `gateway_access_log`;
CREATE TABLE `gateway_access_log`
(
    `id`              bigint NOT NULL AUTO_INCREMENT,
    `trace_id`        varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '链路追踪ID',
    `route_id`        varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '路由ID',
    `request_path`    varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '请求路径',
    `request_method`  varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '请求方法',
    `schema_name`     varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '协议(http/https)',
    `response_status` int NULL DEFAULT NULL COMMENT '响应状态码',
    `client_ip`       varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '客户端IP',
    `duration`        bigint NULL DEFAULT NULL COMMENT '耗时(ms)',
    `request_time`    datetime NULL DEFAULT NULL COMMENT '请求时间',
    `error_msg`       text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '异常信息',
    `target_uri`      varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '目标服务URI',
    `request_body`    text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '请求体',
    `response_body`   text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '响应体',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX             `idx_req_time`(`request_time` ASC) USING BTREE,
    INDEX             `idx_route_id`(`route_id` ASC) USING BTREE
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COMMENT = '网关访问日志';

DROP TABLE IF EXISTS `gateway_app_secret`;
CREATE TABLE `gateway_app_secret`
(
    `app_id`     varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci  NOT NULL COMMENT '应用ID',
    `app_secret` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '应用密钥',
    `remark`     varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
    PRIMARY KEY (`app_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4;


DROP TABLE IF EXISTS `gateway_attack_log`;
CREATE TABLE `gateway_attack_log`
(
    `id`             bigint                                                       NOT NULL AUTO_INCREMENT,
    `trace_id`       varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '链路追踪ID',
    `rule_id`        bigint NULL DEFAULT NULL COMMENT '命中的规则ID',
    `rule_name`      varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '命中的规则名称',
    `attack_type`    varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '攻击类型',
    `client_ip`      varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '攻击者IP',
    `request_uri`    varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '请求路径',
    `request_method` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '请求方法',
    `raw_request`    mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '原始请求报文镜像 (Header + Body)',
    `attack_time`    datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '攻击时间',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX            `idx_attack_time`(`attack_time` ASC) USING BTREE,
    INDEX            `idx_client_ip`(`client_ip` ASC) USING BTREE
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'WAF攻击拦截日志表';

DROP TABLE IF EXISTS `gateway_config_history`;
CREATE TABLE `gateway_config_history`
(
    `id`          bigint                                                        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `config_type` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci  NOT NULL COMMENT '配置类型 (ROUTE, SECURITY_RULE, I18N_MESSAGE, etc.)',
    `config_id`   varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '业务ID (如 route_id, rule_id, username)',
    `snapshot`    longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '配置内容的完整 JSON 快照',
    `operator`    varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci  NOT NULL DEFAULT 'SYSTEM' COMMENT '操作人',
    `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '变更描述或操作备注',
    `create_time` datetime                                                      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX         `idx_type_config_id`(`config_type` ASC, `config_id` ASC) USING BTREE COMMENT '用于快速查询某条配置的历史记录'
) ENGINE = InnoDB AUTO_INCREMENT = 247 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '网关配置版本历史与审计表'  ;

-- ----------------------------
-- Table structure for gateway_dlp_rule
-- ----------------------------
DROP TABLE IF EXISTS `gateway_dlp_rule`;
CREATE TABLE `gateway_dlp_rule`
(
    `id`            bigint                                                        NOT NULL AUTO_INCREMENT,
    `rule_name`     varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci  NOT NULL COMMENT '规则名称 (如: 身份证脱敏)',
    `regex_pattern` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '匹配正则',
    `mask_char`     varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '*' COMMENT '掩码字符',
    `enabled`       tinyint(1) NULL DEFAULT 1 COMMENT '是否启用',
    `description`   varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
    `create_time`   datetime NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'DLP数据防泄漏规则表'  ;

-- ----------------------------
-- Table structure for gateway_limit_rule
-- ----------------------------
DROP TABLE IF EXISTS `gateway_limit_rule`;
CREATE TABLE `gateway_limit_rule`
(
    `id`            bigint                                                        NOT NULL AUTO_INCREMENT,
    `limit_key`     varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci  NOT NULL COMMENT '限流维度: IP, APP_ID, PATH, TENANT',
    `limit_value`   varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '具体值: IP地址, AppId, 路径或租户ID',
    `qps_threshold` int                                                           NOT NULL COMMENT 'QPS阈值',
    `status`        tinyint(1) NULL DEFAULT 1 COMMENT '1-启用, 0-禁用',
    `description`   varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
    `create_time`   datetime NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_key_value`(`limit_key` ASC, `limit_value` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '多租户与精细化限流规则表'  ;

-- ----------------------------
-- Table structure for gateway_mask_field
-- ----------------------------
DROP TABLE IF EXISTS `gateway_mask_field`;
CREATE TABLE `gateway_mask_field`
(
    `field_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '字段名',
    `remark`     varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
    PRIMARY KEY (`field_name`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci  ;

-- ----------------------------
-- Table structure for gateway_projection_field
-- ----------------------------
DROP TABLE IF EXISTS `gateway_projection_field`;
CREATE TABLE `gateway_projection_field`
(
    `field_name`  varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '允许投影的字段名',
    `remark`      varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '备注',
    `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`field_name`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '响应体动态裁剪白名单字段'  ;

-- ----------------------------
-- Table structure for gateway_route
-- ----------------------------
DROP TABLE IF EXISTS `gateway_route`;
CREATE TABLE `gateway_route`
(
    `id`          varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci  NOT NULL,
    `uri`         varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `predicates`  json                                                          NOT NULL,
    `filters`     json NULL,
    `metadata`    json NULL,
    `order_num`   int NULL DEFAULT 0,
    `enabled`     tinyint(1) NULL DEFAULT 1,
    `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '路由描述/备注',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci  ;

-- ----------------------------
-- Table structure for gateway_security_rule
-- ----------------------------
DROP TABLE IF EXISTS `gateway_security_rule`;
CREATE TABLE `gateway_security_rule`
(
    `id`          bigint                                                        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `path`        varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '拦截路径 (AntPath模式)',
    `method`      varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '请求方法 (GET,POST等, 空代表所有)',
    `type`        varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci  NOT NULL COMMENT '规则类型 (PERMIT_ALL, AUTHENTICATED)',
    `priority`    int                                                           NOT NULL DEFAULT 0 COMMENT '优先级 (数值越大越优先)',
    `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 24 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '网关动态安全规则表'  ;

-- ----------------------------
-- Table structure for gateway_waf_rule
-- ----------------------------
DROP TABLE IF EXISTS `gateway_waf_rule`;
CREATE TABLE `gateway_waf_rule`
(
    `id`          bigint                                                        NOT NULL AUTO_INCREMENT,
    `rule_name`   varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '规则名称 (如: Block SQL Injection)',
    `pattern`     varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '正则表达式',
    `match_field` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci  NOT NULL COMMENT '匹配域: BODY, URI, HEADER, QUERY',
    `rule_type`   varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci  NOT NULL COMMENT '规则类型: SQL_INJECTION, XSS, CUSTOM',
    `priority`    int NULL DEFAULT 0 COMMENT '优先级 (数值越大越优先)',
    `enabled`     tinyint(1) NULL DEFAULT 1 COMMENT '是否启用: 1-启用, 0-禁用',
    `description` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '规则描述',
    `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 7 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'WAF防火墙规则表'  ;

-- ----------------------------
-- Table structure for gateway_whitelist
-- ----------------------------
DROP TABLE IF EXISTS `gateway_whitelist`;
CREATE TABLE `gateway_whitelist`
(
    `id`     bigint                                                        NOT NULL AUTO_INCREMENT,
    `path`   varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '拦截路径',
    `type`   varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci  NOT NULL COMMENT '类型: JWT / SIGN',
    `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_type_path`(`type` ASC, `path` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci  ;

-- ----------------------------
-- Table structure for ip_blacklist
-- ----------------------------
DROP TABLE IF EXISTS `ip_blacklist`;
CREATE TABLE `ip_blacklist`
(
    `ip`          varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
    `remark`      varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
    PRIMARY KEY (`ip`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci  ;

-- ----------------------------
-- Table structure for sys_i18n_message
-- ----------------------------
DROP TABLE IF EXISTS `sys_i18n_message`;
CREATE TABLE `sys_i18n_message`
(
    `id`          bigint                                                        NOT NULL AUTO_INCREMENT,
    `msg_key`     varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '消息Key (如 auth.login.error)',
    `locale`      varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci  NOT NULL COMMENT '语言代码 (如 zh_CN, en_US)',
    `message`     varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '消息内容',
    `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP,
    `module`      varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci  NOT NULL DEFAULT 'common' COMMENT '所属模块/服务名',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_mod_key_loc`(`module` ASC, `msg_key` ASC, `locale` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 205 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '国际化消息配置表'  ;

-- ----------------------------
-- Table structure for sys_menu
-- ----------------------------
DROP TABLE IF EXISTS `sys_menu`;
CREATE TABLE `sys_menu`
(
    `id`         bigint                                                       NOT NULL AUTO_INCREMENT,
    `parent_id`  bigint NULL DEFAULT 0,
    `title`      varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `path`       varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '',
    `component`  varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '',
    `perm_code`  varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '',
    `icon`       varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '',
    `type`       tinyint NULL DEFAULT 1,
    `sort_order` int NULL DEFAULT 0,
    PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 31 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci  ;

-- ----------------------------
-- Table structure for sys_role
-- ----------------------------
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role`
(
    `id`   bigint                                                       NOT NULL AUTO_INCREMENT,
    `code` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 5 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci  ;

-- ----------------------------
-- Table structure for sys_role_menu
-- ----------------------------
DROP TABLE IF EXISTS `sys_role_menu`;
CREATE TABLE `sys_role_menu`
(
    `id`      bigint NOT NULL AUTO_INCREMENT,
    `role_id` bigint NOT NULL,
    `menu_id` bigint NOT NULL,
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_role_menu`(`role_id` ASC, `menu_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 209 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci  ;

-- ----------------------------
-- Table structure for sys_user
-- ----------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user`
(
    `id`          bigint                                                        NOT NULL AUTO_INCREMENT,
    `username`    varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci  NOT NULL,
    `password`    varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `enabled`     tinyint(1) NULL DEFAULT 1,
    `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_username`(`username` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 12 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci  ;

-- ----------------------------
-- Table structure for sys_user_role
-- ----------------------------
DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role`
(
    `id`      bigint NOT NULL AUTO_INCREMENT,
    `user_id` bigint NOT NULL,
    `role_id` bigint NOT NULL,
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_user_role`(`user_id` ASC, `role_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 36 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci  ;
