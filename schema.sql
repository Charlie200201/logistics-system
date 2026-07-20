-- ========================================
-- 智能物流追踪系统 - 数据库建表脚本
-- ========================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS db_user DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS db_product DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS db_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE IF NOT EXISTS db_logistics DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- ========================================
-- 用户数据库 (db_user)
-- ========================================
USE db_user;

CREATE TABLE IF NOT EXISTS t_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    username VARCHAR(50) NOT NULL COMMENT '用户名',
    password VARCHAR(128) NOT NULL COMMENT '密码（MD5加密）',
    phone VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ========================================
-- 商品数据库 (db_product)
-- ========================================
USE db_product;

CREATE TABLE IF NOT EXISTS t_product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '商品ID',
    name VARCHAR(200) NOT NULL COMMENT '商品名称',
    description VARCHAR(1000) DEFAULT NULL COMMENT '商品描述',
    price DECIMAL(10, 2) NOT NULL COMMENT '商品价格',
    stock INT NOT NULL DEFAULT 0 COMMENT '库存数量',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- 初始化测试商品
INSERT INTO t_product (name, description, price, stock) VALUES
('iPhone 15', 'Apple iPhone 15 128GB', 5999.00, 100),
('MacBook Pro', 'Apple MacBook Pro 14英寸 M3芯片', 14999.00, 50),
('AirPods Pro', 'Apple AirPods Pro 第二代', 1899.00, 200),
('iPad Air', 'Apple iPad Air 11英寸', 4799.00, 80),
('Apple Watch', 'Apple Watch Series 9', 2999.00, 150);

-- ========================================
-- 订单数据库 (db_order)
-- ========================================
USE db_order;

CREATE TABLE IF NOT EXISTS t_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '订单ID',
    order_no VARCHAR(32) NOT NULL COMMENT '订单号',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    product_id BIGINT NOT NULL COMMENT '商品ID',
    quantity INT NOT NULL COMMENT '购买数量',
    total_amount DECIMAL(10, 2) NOT NULL COMMENT '订单总金额',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_PAYMENT' COMMENT '订单状态: PENDING_PAYMENT-待支付, PAID-已支付, SHIPPED-已发货, DELIVERED-已签收, CANCELLED-已取消',
    address VARCHAR(500) DEFAULT NULL COMMENT '收货地址',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user_id (user_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- ========================================
-- 物流数据库 (db_logistics)
-- ========================================
USE db_logistics;

CREATE TABLE IF NOT EXISTS t_logistics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '物流ID',
    logistics_no VARCHAR(32) NOT NULL COMMENT '物流单号',
    order_id BIGINT NOT NULL COMMENT '关联订单ID',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '物流状态: PENDING-待揽收, IN_TRANSIT-运输中, DELIVERED-已签收',
    current_location VARCHAR(200) DEFAULT NULL COMMENT '当前位置',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_logistics_no (logistics_no),
    KEY idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流表';

CREATE TABLE IF NOT EXISTS t_logistics_track (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '轨迹ID',
    logistics_id BIGINT NOT NULL COMMENT '物流ID',
    location VARCHAR(200) NOT NULL COMMENT '位置',
    description VARCHAR(500) DEFAULT NULL COMMENT '轨迹描述',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_logistics_id (logistics_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流轨迹表';

-- ========================================
-- Seata AT模式 undo_log 表（每个参与分布式事务的数据库都需要）
-- ========================================
USE db_order;
CREATE TABLE IF NOT EXISTS undo_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    branch_id BIGINT NOT NULL COMMENT '分支事务ID',
    xid VARCHAR(100) NOT NULL COMMENT '全局事务ID',
    context VARCHAR(128) NOT NULL COMMENT '上下文',
    rollback_info LONGBLOB NOT NULL COMMENT '回滚信息',
    log_status INT NOT NULL COMMENT '状态: 0-正常, 1-全局已完成',
    log_created DATETIME NOT NULL COMMENT '创建时间',
    log_modified DATETIME NOT NULL COMMENT '修改时间',
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Seata AT模式undo_log表';

USE db_product;
CREATE TABLE IF NOT EXISTS undo_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    branch_id BIGINT NOT NULL,
    xid VARCHAR(100) NOT NULL,
    context VARCHAR(128) NOT NULL,
    rollback_info LONGBLOB NOT NULL,
    log_status INT NOT NULL,
    log_created DATETIME NOT NULL,
    log_modified DATETIME NOT NULL,
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Seata AT模式undo_log表';
