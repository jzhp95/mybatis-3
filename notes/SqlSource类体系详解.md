# SqlSource 类体系详解

## 一、概述

SqlSource 是 MyBatis 映射语句的核心接口之一，负责将原始的 SQL 配置（来自 XML 或注解）转换为可执行的 SQL 语句。它在 MyBatis 的 SQL 解析和执行流程中扮演着至关重要的角色，是连接 SQL 配置与实际数据库操作的关键桥梁。

### 1.1 SqlSource 的核心职责

- **SQL 解析与转换**：将带有占位符和动态元素的原始 SQL 转换为最终可执行的 SQL 字符串
- **参数映射管理**：创建和管理参数映射信息，指导如何从参数对象中提取值
- **动态 SQL 支持**：处理包含条件判断、循环等动态元素的 SQL 语句
- **SQL 编译优化**：通过不同的实现类实现静态 SQL 的预编译优化

### 1.2 在 MyBatis 架构中的位置

```
用户调用
    ↓
Mapper接口方法
    ↓
MappedStatement（包含SqlSource）
    ↓
SqlSource.getBoundSql()
    ↓
BoundSql（最终SQL和参数映射）
    ↓
StatementHandler
    ↓
Executor
    ↓
数据库
```

---

## 二、类继承结构

### 2.1 整体类图

```
┌─────────────────────────────────────────────────────────────┐
│                       SqlSource                              │
│                    (核心接口)                                 │
│  + getBoundSql(Object parameterObject): BoundSql            │
└─────────────────────────────────────────────────────────────┘
                              ↑
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
        ▼                     ▼                     ▼
┌───────────────┐    ┌─────────────────┐    ┌─────────────────┐
│ StaticSqlSource│    │ DynamicSqlSource │    │ ProviderSqlSource│
│ (静态SQL源)     │    │ (动态SQL源)       │    │ (提供者SQL源)     │
└───────────────┘    └─────────────────┘    └─────────────────┘
        │                     │                     │
        │                     │                     │
        │                     │                     │
        ▼                     │                     │
┌───────────────┐             │                     │
│ RawSqlSource  │←────────────┘                     │
│ (原始SQL源)     │                                  │
└───────────────┘                                   │
        │                                           │
        │                                           │
        ▼                                           │
┌───────────────┐                                   │
│ SqlSourceBuilder│←────────────────────────────────┘
│ (SQL源构建器)    │         使用
└───────────────┘
```

### 2.2 类职责一览

| 类名 | 职责 | 出现版本 | 特点 |
|------|------|----------|------|
| SqlSource | 核心接口，定义 SQL 源的标准接口 | 3.0+ | 单方法接口 |
| StaticSqlSource | 静态 SQL 源的简单实现 | 3.0+ | 直接返回 BoundSql |
| DynamicSqlSource | 动态 SQL 源实现 | 3.0+ | 支持动态元素处理 |
| RawSqlSource | 原始静态 SQL 源 | 3.2.0+ | 启动时预解析，性能更优 |
| ProviderSqlSource | SQL 提供者源 | 3.0+ | 通过反射调用提供者方法 |
| SqlSourceBuilder | SQL 源构建器 | 3.0+ | 解析 #{} 占位符 |

---

## 三、核心接口详解

### 3.1 SqlSource 接口

**文件位置**：`org.apache.ibatis.mapping.SqlSource`

**接口定义**：

```java
public interface SqlSource {
  BoundSql getBoundSql(Object parameterObject);
}
```

**核心方法解析**：

| 方法 | 参数 | 返回值 | 职责 |
|------|------|--------|------|
| getBoundSql | parameterObject: 执行 SQL 时传入的参数对象 | BoundSql | 根据参数对象生成包含最终 SQL 和参数映射的 BoundSql 对象 |

**工作流程**：

```
输入: parameterObject（用户传入的实际参数）
    ↓
SqlSource.getBoundSql()
    ↓
处理动态内容（如有）
    ↓
解析参数占位符
    ↓
构建参数映射列表
    ↓
输出: BoundSql（包含最终SQL和参数信息）
```

**关键概念**：

- **parameterObject**：可以是 null、基本类型、Map、POJO 或复杂的参数对象
- **BoundSql**：包含最终可执行的 SQL 字符串和完整的参数映射信息
- **动态 vs 静态**：
  - 静态 SQL：SQL 在编译时已完全确定，只需替换参数
  - 动态 SQL：SQL 结构可能根据参数值而变化

---

## 四、实现类详解

### 4.1 StaticSqlSource - 静态 SQL 源

**文件位置**：`org.apache.ibatis.builder.StaticSqlSource`

**类定义**：

```java
public class StaticSqlSource implements SqlSource {
  private final String sql;
  private final List<ParameterMapping> parameterMappings;
  private final Configuration configuration;

  public StaticSqlSource(Configuration configuration, String sql) {
    this(configuration, sql, null);
  }

  public StaticSqlSource(Configuration configuration, String sql, 
                        List<ParameterMapping> parameterMappings) {
    this.sql = sql;
    this.parameterMappings = parameterMappings;
    this.configuration = configuration;
  }

  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    return new BoundSql(configuration, sql, parameterMappings, parameterObject);
  }
}
```

**核心特点**：

1. **简单直接**：最基础的 SqlSource 实现，没有任何动态能力
2. **不可变设计**：所有字段均为 final，创建后不可修改
3. **直接实例化**：通常由 SqlSourceBuilder 或其他构建器创建

**适用场景**：

- SQL 语句完全不包含动态元素
- 所有 #{} 占位符已在构建时解析完成
- 需要最高性能的场景

**工作流程**：

```
创建 StaticSqlSource
    ↓
存储 sql 字符串和 parameterMappings
    ↓
getBoundSql(parameterObject) 被调用
    ↓
直接创建 BoundSql 对象
    ↓
返回 BoundSql
```

### 4.2 RawSqlSource - 原始静态 SQL 源

**文件位置**：`org.apache.ibatis.scripting.defaults.RawSqlSource`

**类定义**：

```java
public class RawSqlSource implements SqlSource {
  private final SqlSource sqlSource;

  public RawSqlSource(Configuration configuration, SqlNode rootSqlNode, 
                     Class<?> parameterType) {
    this(configuration, getSql(configuration, rootSqlNode), parameterType);
  }

  public RawSqlSource(Configuration configuration, String sql, 
                     Class<?> parameterType) {
    SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
    Class<?> clazz = parameterType == null ? Object.class : parameterType;
    sqlSource = sqlSourceParser.parse(sql, clazz, new HashMap<String, Object>());
  }

  private static String getSql(Configuration configuration, SqlNode rootSqlNode) {
    DynamicContext context = new DynamicContext(configuration, null);
    rootSqlNode.apply(context);
    return context.getSql();
  }

  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    return sqlSource.getBoundSql(parameterObject);
  }
}
```

**核心特点**：

1. **启动时预解析**：在构造时完成 SQL 解析，运行时无解析开销
2. **委托模式**：内部委托解析后的 SqlSource（通常是 StaticSqlSource）执行
3. **比 DynamicSqlSource 快**：因为省去了运行时的解析步骤
4. **支持初始动态处理**：构造函数可以接受 SqlNode，提取静态 SQL 字符串

**与 StaticSqlSource 的区别**：

| 特性 | StaticSqlSource | RawSqlSource |
|------|-----------------|--------------|
| 创建方式 | 直接构造 | 通过 SqlSourceBuilder 解析创建 |
| 参数映射 | 需要外部提供 | 自动从 SQL 中解析 |
| 解析时机 | 创建时提供 | 构造时解析 |
| 动态支持 | 无 | 构造函数支持 SqlNode 预处理 |

**使用场景**：

- Mapper XML 中使用 `<select>`、`<insert>` 等不包含动态元素的标签
- 需要高性能的纯静态 SQL
- SQL 仅有参数替换，没有条件判断、循环等

**工作流程**：

```
RawSqlSource 构造
    ↓
如果是 SqlNode：调用 rootSqlNode.apply() 提取静态 SQL
    ↓
创建 SqlSourceBuilder 解析 SQL
    ↓
生成 StaticSqlSource（包含完整参数映射）
    ↓
存储解析后的 sqlSource
    ↓
getBoundSql(parameterObject)
    ↓
委托给内部 sqlSource 处理
    ↓
返回 BoundSql
```

### 4.3 DynamicSqlSource - 动态 SQL 源

**文件位置**：`org.apache.ibatis.scripting.xmltags.DynamicSqlSource`

**类定义**：

```java
public class DynamicSqlSource implements SqlSource {
  private final Configuration configuration;
  private final SqlNode rootSqlNode;

  public DynamicSqlSource(Configuration configuration, SqlNode rootSqlNode) {
    this.configuration = configuration;
    this.rootSqlNode = rootSqlNode;
  }

  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    // 1. 创建动态上下文
    DynamicContext context = new DynamicContext(configuration, parameterObject);
    
    // 2. 应用 SqlNode 树，构建动态 SQL
    rootSqlNode.apply(context);
    
    // 3. 创建 SQL 解析器
    SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
    
    // 4. 确定参数类型
    Class<?> parameterType = parameterObject == null ? 
        Object.class : parameterObject.getClass();
    
    // 5. 解析动态 SQL，生成 StaticSqlSource
    SqlSource sqlSource = sqlSourceParser.parse(
        context.getSql(), parameterType, context.getBindings());
    
    // 6. 获取 BoundSql
    BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
    
    // 7. 传递额外的动态参数
    for (Map.Entry<String, Object> entry : context.getBindings().entrySet()) {
      boundSql.setAdditionalParameter(entry.getKey(), entry.getValue());
    }
    
    return boundSql;
  }
}
```

**核心特点**：

1. **完整的动态支持**：支持 if、where、foreach、choose 等所有动态元素
2. **运行时解析**：每次执行都需要重新解析 SQL
3. **SqlNode 树结构**：使用组合模式构建动态 SQL
4. **额外的参数支持**：可以创建运行时绑定的变量

**SqlNode 体系**：

```
SqlNode（接口）
    ↑
    ├── MixedSqlNode（混合节点，包含多个 SqlNode）
    ├── TextSqlNode（文本节点，纯文本内容）
    ├── IfSqlNode（if 条件节点）
    ├── WhereSqlNode（where 标签节点）
    ├── SetSqlNode（set 标签节点）
    ├── TrimSqlNode（trim 标签节点）
    ├── ForeachSqlNode（foreach 循环节点）
    ├── ChooseSqlNode（choose 选择了节点）
    └── StaticTextSqlNode（静态文本节点）
```

**工作流程**：

```
getBoundSql(parameterObject)
    ↓
创建 DynamicContext，包装参数对象
    ↓
调用 rootSqlNode.apply(context)
    ↓
遍历 SqlNode 树，根据条件构建 SQL
    ├─ TextSqlNode: 直接追加文本
    ├─ IfSqlNode: 评估条件，条件为真则追加
    ├─ ForeachSqlNode: 遍历集合，循环追加
    └─ ... 其他节点
    ↓
从 context 获取构建好的 SQL 字符串
    ↓
使用 SqlSourceBuilder 解析 #{} 占位符
    ↓
生成 StaticSqlSource
    ↓
获取 BoundSql
    ↓
复制额外的绑定参数
    ↓
返回 BoundSql
```

**性能考虑**：

- 每次执行都会重新构建 SQL，解析 #{} 占位符
- 适用于必须使用动态 SQL 的场景
- 对于纯静态 SQL，考虑使用 RawSqlSource 提升性能

### 4.4 ProviderSqlSource - SQL 提供者源

**文件位置**：`org.apache.ibatis.builder.annotation.ProviderSqlSource`

**类定义**：

```java
public class ProviderSqlSource implements SqlSource {
  private final Configuration configuration;
  private final SqlSourceBuilder sqlSourceParser;
  private final Class<?> providerType;
  private Method providerMethod;
  private String[] providerMethodArgumentNames;
  private Class<?>[] providerMethodParameterTypes;
  private ProviderContext providerContext;
  private Integer providerContextIndex;

  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    SqlSource sqlSource = createSqlSource(parameterObject);
    return sqlSource.getBoundSql(parameterObject);
  }

  private SqlSource createSqlSource(Object parameterObject) {
    // 1. 计算绑定参数数量
    int bindParameterCount = providerMethodParameterTypes.length - 
        (providerContext == null ? 0 : 1);
    
    String sql;
    
    // 2. 根据参数情况调用提供者方法
    if (providerMethodParameterTypes.length == 0) {
      sql = invokeProviderMethod();  // 无参数
    } else if (bindParameterCount == 0) {
      sql = invokeProviderMethod(providerContext);  // 只有 ProviderContext
    } else if (bindParameterCount == 1 && ...) {
      sql = invokeProviderMethod(extractProviderMethodArguments(parameterObject));
    } else if (parameterObject instanceof Map) {
      sql = invokeProviderMethod(
          extractProviderMethodArguments((Map) parameterObject, 
                                       providerMethodArgumentNames));
    } else {
      throw new BuilderException(...);
    }
    
    // 3. 解析 SQL
    Class<?> parameterType = parameterObject == null ? 
        Object.class : parameterObject.getClass();
    return sqlSourceParser.parse(
        replacePlaceholder(sql), parameterType, new HashMap<String, Object>());
  }
}
```

**核心特点**：

1. **Java 代码生成 SQL**：通过调用用户定义的方法动态生成 SQL
2. **反射调用**：使用 Java 反射机制调用提供者方法
3. **多种参数支持**：支持无参数、单个参数、Map 参数、ProviderContext
4. **ProviderContext**：提供 Mapper 方法的上下文信息

**使用示例**：

```java
@Mapper
public interface UserMapper {
  
  @SelectProvider(type = UserSqlProvider.class, method = "selectById")
  User selectById(@Param("id") Long id);
  
  @InsertProvider(type = UserSqlProvider.class, method = "insert")
  int insert(User user);
}

public class UserSqlProvider {
  public static String selectById() {
    return "SELECT * FROM user WHERE id = #{id}";
  }
  
  public static String insert(User user) {
    return "INSERT INTO user(name, age) VALUES(#{name}, #{age})";
  }
}
```

**ProviderContext 的作用**：

```java
public class ProviderContext {
  private final Class<?> mapperType;    // Mapper 接口类型
  private final Method mapperMethod;    // Mapper 方法
  
  // 提供当前 Mapper 方法的元信息
  public Class<?> getMapperType() { ... }
  public Method getMapperMethod() { ... }
}
```

**支持的方法签名**：

| 方法参数 | 说明 |
|---------|------|
| `()` | 无参数方法 |
| `(ProviderContext)` | 仅 ProviderContext |
| `(Object)` | 单个参数 |
| `(ProviderContext, Object)` | ProviderContext + 单个参数 |
| `(Map<String, Object>)` | Map 参数（使用 @Param 命名） |

**工作流程**：

```
创建 ProviderSqlSource
    ↓
解析提供者类型和方法名
    ↓
通过反射查找匹配的方法
    ↓
处理 ProviderContext 参数
    ↓
getBoundSql(parameterObject) 被调用
    ↓
createSqlSource(parameterObject)
    ↓
确定调用方式和参数
    ↓
通过反射调用提供者方法
    ↓
获取生成的 SQL 字符串
    ↓
解析 #{} 占位符
    ↓
生成 StaticSqlSource
    ↓
获取 BoundSql
    ↓
返回 BoundSql
```

---

## 五、辅助类详解

### 5.1 SqlSourceBuilder - SQL 源构建器

**文件位置**：`org.apache.ibatis.builder.SqlSourceBuilder`

**核心职责**：

- 解析 SQL 字符串中的 `#{}` 占位符
- 构建 ParameterMapping 列表
- 创建 StaticSqlSource 实例

**类定义**：

```java
public class SqlSourceBuilder extends BaseBuilder {

  private static final String parameterProperties = 
      "javaType,jdbcType,mode,numericScale,resultMap,typeHandler,jdbcTypeName";

  public SqlSource parse(String originalSql, Class<?> parameterType, 
                        Map<String, Object> additionalParameters) {
    // 1. 创建 Token 处理器
    ParameterMappingTokenHandler handler = 
        new ParameterMappingTokenHandler(configuration, parameterType, 
                                        additionalParameters);
    
    // 2. 创建 SQL 解析器
    GenericTokenParser parser = new GenericTokenParser("#{", "}", handler);
    
    // 3. 解析 SQL
    String sql = parser.parse(originalSql);
    
    // 4. 创建 StaticSqlSource
    return new StaticSqlSource(configuration, sql, 
                              handler.getParameterMappings());
  }
}
```

**ParameterMappingTokenHandler 的工作流程**：

```
GenericTokenParser 遇到 #{xxx}
    ↓
调用 handleToken(content)
    ↓
解析参数属性（javaType、jdbcType 等）
    ↓
确定参数属性类型
    ├─ 从 additionalParameters 获取
    ├─ 使用 TypeHandler 注册表
    ├─ 使用 JdbcType.CURSOR
    ├─ Map 或未知类型使用 Object.class
    └─ 通过反射从 parameterType 获取
    ↓
构建 ParameterMapping 对象
    ↓
返回 "?" 占位符
    ↓
所有占位符处理完成
    ↓
返回 StaticSqlSource
```

**支持的参数属性**：

| 属性 | 说明 | 示例 |
|------|------|------|
| property | 参数属性名（必填） | `#{id}` 或 `#{user.id}` |
| javaType | Java 类型 | `javaType=java.lang.String` |
| jdbcType | JDBC 类型 | `jdbcType=VARCHAR` |
| mode | IN/OUT/INOUT | `mode=IN` |
| numericScale | 数字精度 | `numericScale=2` |
| resultMap | 结果映射 ID | `resultMap=UserResultMap` |
| typeHandler | 类型处理器 | `typeHandler=MyTypeHandler` |
| jdbcTypeName | JDBC 类型名称 | `jdbcTypeName=CURSOR` |

### 5.2 BoundSql - 绑定 SQL

**文件位置**：`org.apache.ibatis.mapping.BoundSql`

**核心职责**：

- 存储最终可执行的 SQL 字符串
- 存储完整的参数映射列表
- 管理额外参数

**类定义**：

```java
public class BoundSql {
  private final String sql;                              // 最终 SQL
  private final List<ParameterMapping> parameterMappings; // 参数映射
  private final Object parameterObject;                  // 参数对象
  private final Map<String, Object> additionalParameters; // 额外参数
  private final MetaObject metaParameters;               // 参数元对象

  public BoundSql(Configuration configuration, String sql, 
                 List<ParameterMapping> parameterMappings, 
                 Object parameterObject) {
    this.sql = sql;
    this.parameterMappings = parameterMappings;
    this.parameterObject = parameterObject;
    this.additionalParameters = new HashMap<>();
    this.metaParameters = configuration.newMetaObject(
        additionalParameters);
  }

  public String getSql() { return sql; }
  public List<ParameterMapping> getParameterMappings() { 
    return parameterMappings; 
  }
  public Object getParameterObject() { return parameterObject; }
  
  public void setAdditionalParameter(String name, Object value) {
    metaParameters.setValue(name, value);
  }
  
  public Object getAdditionalParameter(String name) {
    return metaParameters.getValue(name);
  }
}
```

**ParameterMapping 结构**：

```java
public class ParameterMapping {
  private Configuration configuration;
  private String property;      // 属性名
  private Class<?> javaType;    // Java 类型
  private JdbcType jdbcType;    // JDBC 类型
  private Integer numericScale; // 数字精度
  private String mode;          // 模式
  private TypeHandler<?> typeHandler; // 类型处理器
  private String resultMap;     // 结果映射
  private String jdbcTypeName;  // JDBC 类型名
}
```

### 5.3 SqlNode - SQL 节点接口

**文件位置**：`org.apache.ibatis.scripting.xmltags.SqlNode`

**核心职责**：

- 表示动态 SQL 的一个组成部分
- 应用自身逻辑到 DynamicContext

**接口定义**：

```java
public interface SqlNode {
  boolean apply(DynamicContext context);
}
```

**主要实现类**：

| 实现类 | 职责 |
|--------|------|
| MixedSqlNode | 混合节点，包含多个 SqlNode |
| TextSqlNode | 文本内容节点 |
| StaticTextSqlNode | 静态文本（优化后的 TextSqlNode） |
| IfSqlNode | if 条件判断节点 |
| WhereSqlNode | where 子句智能处理节点 |
| SetSqlNode | set 子句智能处理节点 |
| TrimSqlNode | 通用 trim 处理节点 |
| ForeachSqlNode | foreach 循环节点 |
| ChooseSqlNode | choose/when/otherwise 节点 |

---

## 六、完整工作流程

### 6.1 整体执行流程

```
┌─────────────────────────────────────────────────────────────────┐
│                        MyBatis SQL 执行流程                       │
└─────────────────────────────────────────────────────────────────┘

1. Mapper 配置解析阶段
   ├─ XML 解析器解析 <select|insert|update|delete> 标签
   ├─ 根据内容选择 SqlSource 实现
   │   ├─ 纯静态 → RawSqlSource
   │   ├─ 包含动态 → DynamicSqlSource
   │   └─ 注解 @SelectProvider → ProviderSqlSource
   └─ 构建 MappedStatement

2. SQL 执行阶段
   ├─ SqlSession.xxx(mappedStatementId, parameter)
   ├─ CachingExecutor / BaseExecutor
   │   └─ StatementHandler
   │       └─ PreparedStatementHandler
   │           └─ ParameterHandler
   │               └─ BoundSql
   │                   ├─ getSql() → 最终 SQL
   │                   └─ getParameterMappings() → 参数映射
   └─ 数据库执行
```

### 6.2 静态 SQL 流程（RawSqlSource）

```
用户调用 mapper.selectById(1)
    ↓
获取 MappedStatement
    ↓
获取 SqlSource（RawSqlSource）
    ↓
RawSqlSource.getBoundSql(parameterObject)
    ↓
内部 sqlSource.getBoundSql(parameterObject)
    ↓
StaticSqlSource.getBoundSql(parameterObject)
    ↓
创建 BoundSql
    ├─ sql = "SELECT * FROM user WHERE id = ?"
    ├─ parameterMappings = [{property="id", javaType=Long.class, ...}]
    ├─ parameterObject = 1
    └─ additionalParameters = {}
    ↓
返回 BoundSql
    ↓
ParameterHandler 设置参数
    ↓
执行 SQL
```

### 6.3 动态 SQL 流程（DynamicSqlSource）

```
用户调用 mapper.findUsers(UserQuery query)
    ↓
获取 MappedStatement
    ↓
获取 SqlSource（DynamicSqlSource）
    ↓
DynamicSqlSource.getBoundSql(parameterObject)
    ↓
创建 DynamicContext(parameterObject)
    ↓
rootSqlNode.apply(context)
    ├─ IfSqlNode.apply(): 评估条件 #{name != null}
    │   └─ 条件为真，追加 " AND name = #{name}"
    ├─ IfSqlNode.apply(): 评估条件 #{age != null}
    │   └─ 条件为真，追加 " AND age = #{age}"
    └─ ... 其他节点
    ↓
context.getSql() → "SELECT * FROM user WHERE name = ? AND age = ?"
    ↓
SqlSourceBuilder.parse(sql, parameterType, bindings)
    ├─ 解析 #{name} → ParameterMapping
    ├─ 解析 #{age} → ParameterMapping
    └─ 生成 StaticSqlSource
    ↓
获取 BoundSql
    ↓
复制 additionalParameters（如 #{criteria}）
    ↓
返回 BoundSql
    ↓
执行 SQL
```

### 6.4 Provider SQL 流程（ProviderSqlSource）

```
用户调用 mapper.insert(User)
    ↓
获取 MappedStatement
    ↓
获取 SqlSource（ProviderSqlSource）
    ↓
ProviderSqlSource.getBoundSql(parameterObject)
    ↓
createSqlSource(parameterObject)
    ├─ 计算参数数量
    ├─ 调用 UserSqlProvider.insert(User)
    │   └─ 反射调用，返回 "INSERT INTO user(name,age) VALUES(#{name},#{age})"
    ├─ replacePlaceholder(sql) → 替换 ${} 占位符
    └─ SqlSourceBuilder.parse(sql, User.class, {})
        ├─ 解析 #{name} → ParameterMapping
        ├─ 解析 #{age} → ParameterMapping
        └─ 生成 StaticSqlSource
    ↓
获取 BoundSql
    ↓
返回 BoundSql
    ↓
执行 SQL
```

---

## 七、选择指南

### 7.1 何时使用哪种 SqlSource

| 场景 | 推荐使用 | 原因 |
|------|----------|------|
| 纯静态 SQL（无动态元素） | RawSqlSource | 启动时预解析，性能最优 |
| 静态 SQL + 简单参数替换 | StaticSqlSource | 最简单的实现 |
| 包含 if/foreach 等动态元素 | DynamicSqlSource | 必须使用动态 SQL |
| 需要 Java 代码生成 SQL | ProviderSqlSource | 灵活性最高 |
| 注解配置 SQL | ProviderSqlSource | 注解方式专用 |

### 7.2 性能考虑

```
性能排序（从高到低）：

1. RawSqlSource（预解析，无运行时开销）
   └─ 适合：90% 的使用场景

2. StaticSqlSource（直接构造）
   └─ 适合：需要手动控制参数映射的场景

3. DynamicSqlSource（运行时解析）
   └─ 适合：必须使用动态 SQL 的场景

4. ProviderSqlSource（反射调用 + 解析）
   └─ 适合：需要 Java 代码生成 SQL 的场景

注意：MyBatis 3.2+ 中，RawSqlSource 已经是默认的静态 SQL 实现，
通常无需手动选择。
```

---

## 八、源码流程图

### 8.1 类创建流程

```
XML 配置文件 / 注解
    ↓
XMLMapperBuilder / MapperAnnotationBuilder
    ↓
根据 SQL 内容选择：
    ├─ 纯静态 SQL？
    │   └─ 是 → 调用 SqlSourceBuilder.parse() → RawSqlSource
    │
    ├─ 动态 SQL？
    │   └─ 是 → 构建 SqlNode 树 → DynamicSqlSource
    │
    └─ @SelectProvider 等注解？
        └─ 是 → ProviderSqlSource
    ↓
创建 MappedStatement
    ↓
注册到 Configuration
```

### 8.2 SQL 解析流程

```
原始 SQL（如：SELECT * FROM user WHERE id = #{id}）
    ↓
SqlSourceBuilder.parse()
    ├─ GenericTokenParser 扫描 #{...}
    │   └─ 找到 #{id}
    │
    ├─ ParameterMappingTokenHandler.handleToken("id")
    │   ├─ 解析属性（无额外属性）
    │   ├─ 确定类型（Long.class）
    │   ├─ 创建 ParameterMapping
    │   │   ├─ property = "id"
    │   │   ├─ javaType = Long.class
    │   │   └─ ...
    │   └─ 返回 "?"
    │
    └─ 生成新 SQL："SELECT * FROM user WHERE id = ?"
    ↓
创建 StaticSqlSource(configuration, sql, parameterMappings)
    ↓
返回 StaticSqlSource 实例
```

### 8.3 动态 SQL 构建流程

```
SqlNode 树（如：MixedSqlNode 包含 IfSqlNode, TextSqlNode）
    │
    ├─ TextSqlNode.apply(): 追加 "SELECT * FROM user WHERE 1=1"
    │
    ├─ IfSqlNode.apply(condition: "name != null")
    │   └─ 条件为真 → TextSqlNode.apply(): 追加 " AND name = #{name}"
    │
    ├─ IfSqlNode.apply(condition: "age != null")
    │   └─ 条件为真 → TextSqlNode.apply(): 追加 " AND age = #{age}"
    │
    └─ IfSqlNode.apply(condition: "status != null")
        └─ 条件为假 → 不追加
    ↓
输出 SQL："SELECT * FROM user WHERE 1=1 AND name = ?"
    ↓
后续解析（如有 #{} 占位符）
    ↓
最终 SQL
```

---

## 九、总结

### 9.1 核心要点

1. **SqlSource 是 MyBatis SQL 处理的核心抽象**
   - 定义了统一的接口：`getBoundSql(Object parameterObject)`
   - 屏蔽了静态/动态 SQL 的差异

2. **四种实现类各司其职**
   - `StaticSqlSource`：最基础的静态实现
   - `RawSqlSource`：性能优化的静态实现（推荐）
   - `DynamicSqlSource`：支持完整的动态 SQL
   - `ProviderSqlSource`：支持 Java 代码生成 SQL

3. **SqlSourceBuilder 是 SQL 解析的核心工具**
   - 负责解析 `#{}` 占位符
   - 生成 ParameterMapping 列表
   - 创建 StaticSqlSource

4. **动态 SQL 使用 SqlNode 组合模式**
   - 每个 SqlNode 负责一部分逻辑
   - 组合形成完整的动态 SQL

5. **BoundSql 是最终产物**
   - 包含可执行的 SQL 字符串
   - 包含完整的参数映射
   - 包含额外参数

### 9.2 设计模式应用

| 模式 | 应用场景 |
|------|----------|
| **策略模式** | 不同的 SqlSource 实现对应不同的 SQL 处理策略 |
| **模板方法** | SqlSource 定义算法骨架，具体实现由子类完成 |
| **组合模式** | SqlNode 使用树形结构组合动态 SQL |
| **建造者模式** | SqlSourceBuilder 逐步构建 SqlSource |
| **代理模式** | RawSqlSource 代理内部的 StaticSqlSource |
| **工厂方法** | ProviderSqlSource 内部创建不同类型的 SqlSource |

### 9.3 扩展点

- **自定义 SqlSource**：实现 SqlSource 接口创建自定义 SQL 源
- **自定义 SqlNode**：扩展动态 SQL 能力
- **自定义 TokenHandler**：处理特殊的占位符语法
- **自定义 ParameterMapping**：添加额外的参数映射信息

---

## 十、参考资料

- MyBatis 官方文档：SQL 语句构建器
- 源码位置：`org.apache.ibatis.mapping.SqlSource`
- 相关类：
  - `org.apache.ibatis.mapping.BoundSql`
  - `org.apache.ibatis.builder.SqlSourceBuilder`
  - `org.apache.ibatis.scripting.xmltags.SqlNode`
