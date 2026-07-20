# 12 — MyBatis-Plus ORM 框架

## 是什么

MyBatis-Plus 是 MyBatis 的增强工具。你只需要**继承 `BaseMapper`**，就能自动获得全套 CRUD 方法，连 SQL 都不用写。

## 为什么需要

### 传统 MyBatis：每个操作都要写 SQL

```xml
<!-- UserMapper.xml — 每个方法都要写 -->
<select id="selectById" resultType="User">
    SELECT id, username, password, phone, created_at, updated_at
    FROM t_user WHERE id = #{id}
</select>
<insert id="insert">
    INSERT INTO t_user (username, password, phone, created_at)
    VALUES (#{username}, #{password}, #{phone}, NOW())
</insert>
<update id="updateById">
    UPDATE t_user SET username=#{username}, password=#{password}, ...
    WHERE id=#{id}
</update>
<delete id="deleteById">
    DELETE FROM t_user WHERE id=#{id}
</delete>
```

### MyBatis-Plus：零 SQL 零 XML

```java
@Mapper
public interface UserMapper extends BaseMapper<User> {
    // 什么都不用写！
}

// 自动获得的方法：
userMapper.insert(user);              // 插入
userMapper.deleteById(1L);            // 根据ID删除
userMapper.updateById(user);          // 根据ID更新
userMapper.selectById(1L);            // 根据ID查询
userMapper.selectList(wrapper);       // 条件查询
userMapper.selectPage(page, wrapper); // 分页查询
```

## 核心概念

### 实体类映射

**文件位置**: `user-service/src/main/java/com/logistics/user/entity/User.java`

```java
@Data
@TableName("t_user")          // ① 指定表名（默认类名下划线转蛇形）
public class User {

    @TableId(type = IdType.AUTO)   // ② 主键自增
    private Long id;

    private String username;       // ③ 自动映射 username → username 列

    private String password;

    private String phone;

    @TableField(fill = FieldFill.INSERT)  // ④ 插入时自动填充
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)  // ⑤ 插入和更新时自动填充
    private LocalDateTime updatedAt;
}
```

**命名转换规则**（`map-underscore-to-camel-case: true`）：

| Java 字段 | 数据库列 |
|-----------|----------|
| `createdAt` | `created_at` |
| `orderNo` | `order_no` |
| `totalAmount` | `total_amount` |

### 各服务的实体类

| 服务 | 实体类 | 表名 | 数据库 |
|------|--------|------|--------|
| user-service | User.java | t_user | db_user |
| product-service | Product.java | t_product | db_product |
| order-service | Order.java | t_order | db_order |
| logistics-service | Logistics.java | t_logistics | db_logistics |
| logistics-service | LogisticsTrack.java | t_logistics_track | db_logistics |

## 项目中的代码

### 1. Mapper 接口（零代码 CRUD）

**文件位置**: `product-service/src/main/java/com/logistics/product/mapper/ProductMapper.java`

```java
@Mapper
public interface ProductMapper extends BaseMapper<Product> {
    // 继承 BaseMapper，自动拥有所有 CRUD 方法
}
```

四个服务的 Mapper 都是这样，只继承 `BaseMapper`，不写任何方法。

### 2. Service 继承 ServiceImpl

**文件位置**: `product-service/src/main/java/com/logistics/product/service/impl/ProductServiceImpl.java`

```java
@Service
public class ProductServiceImpl
        extends ServiceImpl<ProductMapper, Product>  // ① 继承 ServiceImpl
        implements ProductService {

    // ② 可以直接用 this.xxx() 调用方法
    public Product getProductById(Long id) {
        Product product = this.getById(id);       // 根据ID查询
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        return product;
    }

    public Product create(Product product) {
        this.save(product);                        // 保存
        return product;
    }

    public Product update(Product product) {
        this.updateById(product);                  // 更新
        return product;
    }

    public void delete(Long id) {
        this.removeById(id);                       // 删除
    }
}
```

### 3. 条件查询（LambdaQueryWrapper）

```java
// 传统方式（容易写错字段名）:
wrapper.eq("username", "testuser")

// Lambda 方式（类型安全，IDE 有提示）:
wrapper.eq(User::getUsername, "testuser")
```

**文件位置**: `order-service/src/main/java/com/logistics/order/service/impl/OrderServiceImpl.java`

```java
// 查询某用户的所有订单
public List<Order> getOrdersByUserId(Long userId) {
    LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
    wrapper.eq(Order::getUserId, userId)           // WHERE user_id = ?
           .orderByDesc(Order::getCreatedAt);       // ORDER BY created_at DESC
    return this.list(wrapper);                      // 执行查询
}

// 查询超时未支付的订单
public List<Order> getExpiredOrders(int minutes) {
    LocalDateTime deadline = LocalDateTime.now().minusMinutes(minutes);
    LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
    wrapper.eq(Order::getStatus, "PENDING_PAYMENT")   // WHERE status = 'PENDING_PAYMENT'
           .lt(Order::getCreatedAt, deadline);         // AND created_at < deadline
    return this.list(wrapper);
}
```

### 常用条件

| 方法 | SQL | 示例 |
|------|-----|------|
| `eq` | `=` | `eq(User::getId, 1L)` |
| `ne` | `<>` | `ne(User::getStatus, "DELETED")` |
| `gt` | `>` | `gt(Product::getStock, 0)` |
| `lt` | `<` | `lt(Order::getCreatedAt, deadline)` |
| `ge` | `>=` | `ge(Product::getPrice, 100)` |
| `like` | `LIKE` | `like(Product::getName, "手机")` |
| `in` | `IN` | `in(Order::getStatus, "PAID", "SHIPPED")` |
| `between` | `BETWEEN` | `between(Order::getCreatedAt, start, end)` |
| `orderByAsc` | `ORDER BY ASC` | `orderByAsc(Order::getCreatedAt)` |
| `orderByDesc` | `ORDER BY DESC` | `orderByDesc(Order::getCreatedAt)` |
| `last` | 末尾追加 | `last("LIMIT 10")` |

### 4. 分页查询

```java
// product-service 的商品分页
public Page<Product> pageQuery(int pageNum, int pageSize) {
    Page<Product> page = new Page<>(pageNum, pageSize);    // 第 pageNum 页，每页 pageSize 条
    return this.page(page,
        new LambdaQueryWrapper<Product>().orderByDesc(Product::getCreatedAt)
    );
}
// page.getRecords()  → 当前页数据列表
// page.getTotal()    → 总记录数
// page.getPages()    → 总页数
```

### 5. application.yml 配置

```yaml
mybatis-plus:
  mapper-locations: classpath:mapper/*.xml     # XML 映射文件位置（本项目不用 XML）
  type-aliases-package: com.logistics.user.entity  # 实体类包路径
  configuration:
    map-underscore-to-camel-case: true          # 下划线转驼峰
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl  # 控制台打印 SQL
```

### 6. 依赖

每个有数据库操作的服务的 `pom.xml`：

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-boot-starter</artifactId>
</dependency>
<dependency>
    <groupId>mysql</groupId>
    <artifactId>mysql-connector-java</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>druid-spring-boot-starter</artifactId>  <!-- 连接池 -->
</dependency>
```

## 验证方法

启动服务后看控制台，MyBatis-Plus 会自动打印 SQL：

```
==>  Preparing: SELECT id,username,password,phone,created_at,updated_at FROM t_user WHERE id=?
==> Parameters: 1(Long)
<==      Total: 1
```

## 常见问题

**Q: 插入时 created_at 没有自动填充？**
A: 加 `@TableField(fill = FieldFill.INSERT)`，或者数据库列设 `DEFAULT CURRENT_TIMESTAMP`。

**Q: 实体类字段名和数据库列名不一致？**
A: 确认 `map-underscore-to-camel-case: true`。或用 `@TableField("column_name")` 显式指定。

**Q: 想用 XML 写复杂 SQL？**
A: 可以，在 Mapper 接口中定义方法，在 `mapper/` 目录下写同名的 XML 文件即可。
