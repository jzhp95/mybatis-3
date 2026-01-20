# MyBatis Builder 体系深入解析

## 概述

MyBatis 的 Builder 体系是整个框架配置解析的核心架构，采用模板方法模式设计，提供了一套完整的配置构建机制。该体系由抽象基类 `BaseBuilder` 定义通用功能，各个具体 Builder 类负责解析不同类型的配置文件和注解，最终将配置信息聚合到 `Configuration` 对象中。整个 Builder 体系的设计遵循了单一职责原则，每个 Builder 类都有明确的职责边界，通过链式调用和协作完成复杂的配置解析任务。理解 Builder 体系的架构设计对于深入掌握 MyBatis 的初始化流程、配置解析机制以及扩展定制具有重要意义。在实际开发中，开发者可以通过了解 Builder 体系来理解 MyBatis 如何将 XML 配置文件和注解转换为内部可用的配置对象，这对于问题排查、性能调优和功能扩展都具有重要价值。

## 继承体系结构

### 类继承关系图

```
java.lang.Object
    └── org.apache.ibatis.builder.BaseBuilder（抽象基类）
            │
            ├── org.apache.ibatis.builder.xml.XMLConfigBuilder
            │       │
            │       └── 职责：解析 mybatis-config.xml 主配置文件
            │
            ├── org.apache.ibatis.builder.xml.XMLMapperBuilder
            │       │
            │       └── 职责：解析 Mapper XML 映射文件
            │
            ├── org.apache.ibatis.builder.xml.XMLStatementBuilder
            │       │
            │       └── 职责：解析单个 SQL 语句节点
            │
            ├── org.apache.ibatis.builder.MapperBuilderAssistant
            │       │
            │       └── 职责：提供 Mapper 构建的辅助操作
            │
            ├── org.apache.ibatis.scripting.xmltags.XMLScriptBuilder
            │       │
            │       └── 职责：解析动态 SQL 脚本
            │
            └── org.apache.ibatis.builder.SqlSourceBuilder
                    │
                    └── 职责：构建 SqlSource 对象

// 相关构建器（不继承 BaseBuilder）
├── org.apache.ibatis.session.SqlSessionFactoryBuilder
│       │
│       └── 职责：构建 SqlSessionFactory，入口类
│
├── org.apache.ibatis.mapping.CacheBuilder
│       │
│       └── 职责：构建缓存对象
│
└── org.apache.ibatis.builder.annotation.MapperAnnotationBuilder
        │
        └── 职责：解析 Mapper 接口中的注解
```

### 各 Builder 类的职责定位

| 类名 | 继承关系 | 核心职责 | 解析目标 |
|------|---------|---------|---------|
| BaseBuilder | 抽象基类 | 提供通用类型解析和实例创建工具方法 | 无 |
| XMLConfigBuilder | extends BaseBuilder | 解析 mybatis-config.xml 主配置文件 | 主配置文件 |
| XMLMapperBuilder | extends BaseBuilder | 解析 Mapper XML 映射文件 | 映射文件 |
| XMLStatementBuilder | extends BaseBuilder | 解析单个 SQL 语句节点 | select/insert/update/delete |
| MapperBuilderAssistant | extends BaseBuilder | 提供 Mapper 构建的辅助操作 | 通用构建操作 |
| XMLScriptBuilder | extends BaseBuilder | 解析动态 SQL 脚本 | where/foreach/if 等标签 |
| SqlSourceBuilder | extends BaseBuilder | 构建 SqlSource 对象 | #{} 占位符解析 |
| SqlSessionFactoryBuilder | 独立类 | 构建 SqlSessionFactory | 入口点 |
| CacheBuilder | 独立类 | 构建缓存对象 | 缓存配置 |
| MapperAnnotationBuilder | 独立类 | 解析 Mapper 接口注解 | @Select/@Insert 等 |

## BaseBuilder 抽象基类详解

### 类结构分析

`BaseBuilder` 是整个 Builder 体系的核心抽象基类，它定义了所有子构建器需要共享的通用功能和工具方法。该类的主要职责包括：持有共享的 `Configuration` 对象、访问类型别名注册表和类型处理器注册表、提供类型解析和实例创建的通用方法。通过继承 `BaseBuilder`，各个具体 Builder 类可以避免重复编写相同的类型转换和解析逻辑，从而保持代码的简洁性和一致性。

### 核心字段

```java
public abstract class BaseBuilder {
    // 核心配置对象，包含所有配置信息
    protected final Configuration configuration;
    
    // 类型别名注册表，用于解析字符串形式的类名
    protected final TypeAliasRegistry typeAliasRegistry;
    
    // 类型处理器注册表，用于处理 Java 类型与 JDBC 类型的转换
    protected final TypeHandlerRegistry typeHandlerRegistry;
}
```

这三个核心字段构成了 Builder 类与 MyBatis 配置系统的连接桥梁。`Configuration` 对象是整个框架的核心数据容器，包含了所有已解析的配置信息；`TypeAliasRegistry` 提供了将字符串别名转换为完整类名的能力；`TypeHandlerRegistry` 则管理了各种类型处理器，用于在 Java 类型和 JDBC 类型之间进行转换。

### 构造方法

```java
public BaseBuilder(Configuration configuration) {
    this.configuration = configuration;
    this.typeAliasRegistry = this.configuration.getTypeAliasRegistry();
    this.typeHandlerRegistry = this.configuration.getTypeHandlerRegistry();
}
```

构造方法的设计体现了依赖注入的思想，具体的 `Configuration` 对象由外部传入，这样设计的好处是：它使得 Builder 类可以接受任何已配置的 Configuration 对象，增强了灵活性；同时也使得测试更加容易，因为可以在测试中传入模拟的 Configuration 对象。此外，通过一次赋值同时初始化三个核心字段，避免了在各个子类中重复编写初始化代码。

### 通用类型解析方法

`BaseBuilder` 提供了一系列受保护的类型解析工具方法，这些方法是整个 Builder 体系进行类型处理的基础：

```java
// 解析正则表达式，如果为空则使用默认值
protected Pattern parseExpression(String regex, String defaultValue) {
    return Pattern.compile(regex == null ? defaultValue : regex);
}

// 将字符串转换为布尔值
protected Boolean booleanValueOf(String value, Boolean defaultValue) {
    return value == null ? defaultValue : Boolean.valueOf(value);
}

// 将字符串转换为整数值
protected Integer integerValueOf(String value, Integer defaultValue) {
    return value == null ? defaultValue : Integer.valueOf(value);
}

// 将逗号分隔的字符串解析为 Set 集合
protected Set<String> stringSetValueOf(String value, String defaultValue) {
    value = (value == null ? defaultValue : value);
    return new HashSet<String>(Arrays.asList(value.split(",")));
}
```

这些辅助方法统一了配置值的解析逻辑，每个方法都处理了空值情况并提供了默认值支持，有效避免了空指针异常和解析错误。

### JDBC 类型解析方法

```java
// 解析 JDBC 类型别名
protected JdbcType resolveJdbcType(String alias) {
    if (alias == null) {
        return null;
    }
    try {
        return JdbcType.valueOf(alias);
    } catch (IllegalArgumentException e) {
        throw new BuilderException("Error resolving JdbcType. Cause: " + e, e);
    }
}

// 解析结果集类型
protected ResultSetType resolveResultSetType(String alias) {
    if (alias == null) {
        return null;
    }
    try {
        return ResultSetType.valueOf(alias);
    } catch (IllegalArgumentException e) {
        throw new BuilderException("Error resolving ResultSetType. Cause: " + e, e);
    }
}

// 解析参数模式（IN/OUT/INOUT）
protected ParameterMode resolveParameterMode(String alias) {
    if (alias == null) {
        return null;
    }
    try {
        return ParameterMode.valueOf(alias);
    } catch (IllegalArgumentException e) {
        throw new BuilderException("Error resolving ParameterMode. Cause: " + e, e);
    }
}
```

这些方法将字符串形式的配置值转换为枚举类型，在转换失败时抛出带有详细信息的 `BuilderException`，便于开发者定位配置错误。

### 实例创建与类型解析方法

```java
// 根据别名创建对象实例
protected Object createInstance(String alias) {
    Class<?> clazz = resolveClass(alias);
    if (clazz == null) {
        return null;
    }
    try {
        return resolveClass(alias).newInstance();
    } catch (Exception e) {
        throw new BuilderException("Error creating instance. Cause: " + e, e);
    }
}

// 解析字符串别名得到 Class 对象
protected Class<?> resolveClass(String alias) {
    if (alias == null) {
        return null;
    }
    try {
        return resolveAlias(alias);
    } catch (Exception e) {
        throw new BuilderException("Error resolving class. Cause: " + e, e);
    }
}

// 解析类型别名（委托给 TypeAliasRegistry）
protected Class<?> resolveAlias(String alias) {
    return typeAliasRegistry.resolveAlias(alias);
}
```

这套方法实现了从配置字符串到实际 Java 对象的完整转换链条：`resolveClass` 负责解析类名，`createInstance` 负责根据解析得到的 Class 对象创建实例。整个过程都有完善的异常处理，确保配置错误能够被及时发现和报告。

### 类型处理器解析方法

```java
// 根据别名解析类型处理器
protected TypeHandler<?> resolveTypeHandler(Class<?> javaType, String typeHandlerAlias) {
    if (typeHandlerAlias == null) {
        return null;
    }
    Class<?> type = resolveClass(typeHandlerAlias);
    if (type != null && !TypeHandler.class.isAssignableFrom(type)) {
        throw new BuilderException("Type " + type.getName() + 
            " is not a valid TypeHandler because it does not implement TypeHandler interface");
    }
    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler<?>> typeHandlerType = (Class<? extends TypeHandler<?>>) type;
    return resolveTypeHandler(javaType, typeHandlerType);
}

// 解析并获取类型处理器实例
protected TypeHandler<?> resolveTypeHandler(Class<?> javaType, 
        Class<? extends TypeHandler<?>> typeHandlerType) {
    if (typeHandlerType == null) {
        return null;
    }
    TypeHandler<?> handler = typeHandlerRegistry.getMappingTypeHandler(typeHandlerType);
    if (handler == null) {
        handler = typeHandlerRegistry.getInstance(javaType, typeHandlerType);
    }
    return handler;
}
```

类型处理器解析是 MyBatis 类型系统的核心功能之一。这两个方法首先检查类型处理器是否实现了 `TypeHandler` 接口，然后尝试从注册表中获取已存在的处理器实例，如果不存在则创建新的实例。这种设计避免了重复创建相同类型的处理器实例，提高了内存使用效率。

## XMLConfigBuilder 详解

### 类概述

`XMLConfigBuilder` 是 MyBatis 配置解析体系中的核心类，负责解析 `mybatis-config.xml` 主配置文件。它是整个配置构建流程的入口点，通过调用 `parse()` 方法触发配置解析，并将解析结果填充到 `Configuration` 对象中。该类的设计遵循了建造者模式，通过链式调用和各种配置解析方法的组合，完成了复杂的配置初始化工作。`XMLConfigBuilder` 的实例只能使用一次，这是为了防止重复解析导致配置状态不一致的问题。

### 核心字段

```java
public class XMLConfigBuilder extends BaseBuilder {
    // 标记是否已解析，防止重复解析
    private boolean parsed;
    
    // XPath 解析器，用于解析 XML 配置文件
    private final XPathParser parser;
    
    // 环境标识，用于指定使用哪个环境配置
    private String environment;
    
    // 本地反射器工厂，用于创建反射器对象
    private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();
}
```

### 构造方法

```java
// 支持多种构造方式，适配不同的输入源
public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
}

public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
}

public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), 
         environment, props);
}

// InputStream 版本的构造方法
public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
}

public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
}

public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), 
         environment, props);
}

// 私有构造方法，实际初始化逻辑
private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
}
```

构造方法的设计体现了灵活性原则，支持 Reader 和 InputStream 两种输入源，支持可选的环境标识和属性配置。私有构造方法 `XMLConfigBuilder(XPathParser parser, String environment, Properties props)` 是实际执行初始化的方法，它创建了新的 `Configuration` 对象，并设置了各种配置参数。

### 核心解析方法

```java
public Configuration parse() {
    if (parsed) {
        throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    parsed = true;
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
}
```

`parse()` 方法是整个配置解析的入口点。它首先检查是否已经解析过，如果已解析则抛出异常；然后设置解析标记为 true，防止后续重复调用；接着调用 `parseConfiguration()` 方法执行实际的解析工作；最后返回解析完成的 `Configuration` 对象。这种设计确保了每个 `XMLConfigBuilder` 实例只能被使用一次，避免了配置状态不一致的问题。

### 配置解析流程详解

```java
private void parseConfiguration(XNode root) {
    try {
        // 第一步：解析 properties 节点
        // 原因：properties 必须最先解析，因为后续所有配置都可能使用 ${} 占位符
        propertiesElement(root.evalNode("properties"));
        
        // 第二步：解析 settings 节点
        Properties settings = settingsAsProperties(root.evalNode("settings"));
        
        // 第三步：加载自定义 VFS 实现
        loadCustomVfs(settings);
        
        // 第四步：解析类型别名配置
        typeAliasesElement(root.evalNode("typeAliases"));
        
        // 第五步：解析插件配置
        pluginElement(root.evalNode("plugins"));
        
        // 第六步：解析对象工厂配置
        objectFactoryElement(root.evalNode("objectFactory"));
        
        // 第七步：解析对象包装器工厂配置
        objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
        
        // 第八步：解析反射器工厂配置
        reflectorFactoryElement(root.evalNode("reflectorFactory"));
        
        // 第九步：应用 settings 配置到 Configuration 对象
        settingsElement(settings);
        
        // 第十步：解析环境配置
        environmentsElement(root.evalNode("environments"));
        
        // 第十一步：解析数据库标识提供者配置
        databaseIdProviderElement(root.evalNode("databaseIdProvider"));
        
        // 第十二步：解析类型处理器配置
        typeHandlerElement(root.evalNode("typeHandlers"));
        
        // 最后一步：解析映射器配置
        mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
        throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
}
```

`parseConfiguration()` 方法是配置解析的核心，它按照严格的顺序依次解析各个配置元素。解析顺序的设计是有其深层原因的：

1. **properties 必须最先**：因为后续所有配置都可能使用 `${}` 占位符引用属性值，如果不在最开始解析，后续的配置替换将无法正确工作。

2. **settings 在 VFS 之前**：需要先提取 settings 的属性，但实际应用需要等到 VFS 加载完成之后。

3. **typeAliases 在 plugins 之前**：类型别名可能被插件引用。

4. **objectFactory/wrapper 在 environments 之前**：根据 issue #631 的说明，某些设置可能影响环境配置的处理。

5. **mappers 最后**：因为映射器需要前面所有的配置信息（别名、类型处理器、插件等）才能正确初始化。

## XMLMapperBuilder 详解

### 类概述

`XMLMapperBuilder` 负责解析 Mapper XML 映射文件，将映射文件中定义的 SQL 语句、结果映射、缓存配置等注册到 `Configuration` 对象中。与 `XMLConfigBuilder` 不同，`XMLMapperBuilder` 可以被多次调用以加载多个映射文件。该类在内部使用 `MapperBuilderAssistant` 来执行具体的构建操作，这种设计将解析逻辑（`XMLMapperBuilder`）和构建操作（`MapperBuilderAssistant`）分离，提高了代码的可维护性。`XMLMapperBuilder` 还维护了 `sqlFragments` 映射，用于存储和复用 `<sql>` 片段，这是 MyBatis 支持 SQL 重用的重要机制。

### 核心字段

```java
public class XMLMapperBuilder extends BaseBuilder {
    // XPath 解析器，用于解析 Mapper XML 文件
    private final XPathParser parser;
    
    // Mapper 构建助手，实际执行构建操作
    private final MapperBuilderAssistant builderAssistant;
    
    // SQL 片段映射，存储 <sql> 片段供 <include> 使用
    private final Map<String, XNode> sqlFragments;
    
    // 当前解析的资源名称（通常是文件路径）
    private final String resource;
}
```

### 构造方法

```java
@Deprecated
public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, 
        Map<String, XNode> sqlFragments, String namespace) {
    this(reader, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
}

@Deprecated
public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, 
        Map<String, XNode> sqlFragments) {
    this(new XPathParser(reader, true, configuration.getVariables(), 
          new XMLMapperEntityResolver()),
         configuration, resource, sqlFragments);
}

public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, 
        Map<String, XNode> sqlFragments, String namespace) {
    this(inputStream, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
}

public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, 
        Map<String, XNode> sqlFragments) {
    this(new XPathParser(inputStream, true, configuration.getVariables(), 
          new XMLMapperEntityResolver()),
         configuration, resource, sqlFragments);
}

private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, 
        Map<String, XNode> sqlFragments) {
    super(configuration);
    this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
    this.parser = parser;
    this.sqlFragments = sqlFragments;
    this.resource = resource;
}
```

### 核心解析方法

```java
public void parse() {
    // 检查资源是否已加载，避免重复解析
    if (!configuration.isResourceLoaded(resource)) {
        // 递归加载 Mapper 接口及其 XML 文件
        configuration.addLoadedResource(resource);
        
        // 设置当前 Mapper 的命名空间
        builderAssistant.setCurrentNamespace(getSqlNodeStringValue(context.evalNode("namespace")));
        
        // 解析二级缓存配置
        parseCache();
        
        // 解析缓存引用配置
        parseCacheRef();
        
        // 解析参数映射配置（已废弃，现在通过 ParameterMap 节点解析）
        ParameterMapBuilder parameterMapBuilder = parseParameterMap();
        
        // 解析结果映射配置
        List<ResultMap> resultMaps = new ArrayList<ResultMap>();
        parseResultMaps(resultMaps);
        
        // 解析 SQL 语句节点
        parseStatements();
    }
    
    // 处理之前解析失败的元素
    parsePendingResultMaps();
    parsePendingCacheRefStatements();
    parsePendingStatements();
}
```

`parse()` 方法的执行流程如下：首先检查资源是否已加载，避免重复解析；然后设置当前 Mapper 的命名空间；接着依次解析缓存配置、缓存引用、参数映射、结果映射和 SQL 语句；最后处理之前因依赖未满足而延迟解析的元素。这种设计确保了即使存在循环依赖，也能通过多次遍历完成所有解析工作。

## XMLStatementBuilder 详解

### 类概述

`XMLStatementBuilder` 负责解析 Mapper XML 文件中的单个 SQL 语句节点（select、insert、update、delete），将其转换为 `MappedStatement` 对象并注册到 `Configuration` 中。该类是 SQL 语句解析的核心，每个 SQL 语句节点都会创建一个 `XMLStatementBuilder` 实例进行处理。`XMLStatementBuilder` 内部使用了 `XMLIncludeTransformer` 来处理 `<include>` 标签的 SQL 片段引用，使用 `XMLScriptBuilder` 来解析动态 SQL 内容。这种分工明确的设计使得每个类只需要关注自己的职责范围，提高了代码的可读性和可维护性。

### 核心方法

```java
public void parseStatementNode() {
    // 获取语句的唯一标识符和数据库标识符
    String id = context.getStringAttribute("id");
    String databaseId = context.getStringAttribute("databaseId");
    
    // 检查数据库标识符是否匹配
    if (!databaseMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
        return;
    }
    
    // 提取基础配置属性
    Integer fetchSize = context.getIntAttribute("fetchSize");
    Integer timeout = context.getIntAttribute("timeout");
    String parameterMap = context.getStringAttribute("parameterMap");
    String parameterType = context.getStringAttribute("parameterType");
    Class<?> parameterTypeClass = resolveClass(parameterType);
    String resultMap = context.getStringAttribute("resultMap");
    String resultType = context.getStringAttribute("resultType");
    String lang = context.getStringAttribute("lang");
    LanguageDriver langDriver = getLanguageDriver(lang);
    
    // 解析结果类型和结果集类型配置
    Class<?> resultTypeClass = resolveClass(resultType);
    String resultSetType = context.getStringAttribute("resultSetType");
    StatementType statementType = StatementType.valueOf(
        context.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    ResultSetType resultSetTypeEnum = resolveResultSetType(resultSetType);
    
    // 根据节点名称确定 SQL 命令类型
    String nodeName = context.getNode().getNodeName();
    SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));
    boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
    
    // 设置缓存策略
    boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect);
    boolean useCache = context.getBooleanAttribute("useCache", isSelect);
    boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false);
    
    // 处理 <include> 标签
    XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
    includeParser.applyIncludes(context.getNode());
    
    // 解析 SelectKey 节点（用于插入操作获取自增ID）
    processSelectKeyNodes(id, parameterTypeClass, langDriver);
    
    // 解析 SQL 内容为 SqlSource 对象
    SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);
    
    // 构建 MappedStatement 并注册到 Configuration
    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered,
        keyGenerator, keyProperties, keyColumns, databaseId, langDriver, resultSets);
}
```

`parseStatementNode()` 方法的执行流程：首先获取语句的基本属性（id、databaseId 等）；然后提取配置属性（fetchSize、timeout 等）；接着确定 SQL 命令类型（select/insert/update/delete）并设置缓存策略；之后处理 `<include>` 标签引用；然后解析 `<selectKey>` 节点（用于插入操作获取自增 ID）；最后将解析结果封装为 `MappedStatement` 并注册到 `Configuration` 中。

## MapperBuilderAssistant 详解

### 类概述

`MapperBuilderAssistant` 是 Mapper 构建过程中的辅助工具类，提供了各种构建操作的实现方法。它继承了 `BaseBuilder` 的类型解析能力，同时提供了丰富的构建方法供 `XMLMapperBuilder`、`XMLStatementBuilder` 和 `MapperAnnotationBuilder` 使用。该类的核心职责是构建 `MappedStatement`、`ResultMap`、`ParameterMap` 等配置对象，并将它们注册到 `Configuration` 中。`MapperBuilderAssistant` 使用 `currentNamespace` 字段来跟踪当前正在处理的 Mapper 命名空间，确保所有构建的操作都关联到正确的命名空间下。

### 核心字段

```java
public class MapperBuilderAssistant extends BaseBuilder {
    // 当前处理的命名空间
    private String currentNamespace;
    
    // 当前处理 Mapper 的资源路径
    private final String resource;
    
    // 当前 Mapper 的缓存配置
    private Cache currentCache;
    
    // 标记是否存在未解析的缓存引用
    private boolean unresolvedCacheRef;
}
```

### 核心构建方法

```java
// 添加映射语句
public MappedStatement addMappedStatement(String id, SqlSource sqlSource, 
        StatementType statementType, SqlCommandType sqlCommandType,
        Integer fetchSize, Integer timeout, String parameterMap, Class<?> parameterType,
        String resultMap, Class<?> resultType, ResultSetType resultSetType,
        boolean flushCache, boolean useCache, boolean resultOrdered,
        KeyGenerator keyGenerator, String[] keyProperties, String[] keyColumns,
        String databaseId, LanguageDriver langDriver, String[] resultSets) {
    // 构建完整的语句 ID（包含命名空间）
    id = applyCurrentNamespace(id, false);
    boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
    
    // 创建 MappedStatement.Builder 并配置各项属性
    MappedStatement.Builder statementBuilder = new MappedStatement.Builder(
        configuration, id, sqlSource, sqlCommandType)
        .resource(resource)
        .fetchSize(fetchSize)
        .timeout(timeout)
        .statementType(statementType)
        .keyGenerator(keyGenerator)
        .keyProperties(keyProperties)
        .keyColumns(keyColumns)
        .databaseId(databaseId)
        .resultOrdered(resultOrdered)
        .flushCacheRequired(flushCache)
        .useCache(useCache)
        .cache(currentCache);
    
    // 设置参数映射
    if (parameterMap != null) {
        String parameterType = applyCurrentNamespace(parameterMap, false);
        statementBuilder.parameterMap(
            new ParameterMap.Builder(configuration, parameterType + "-Inline", 
                parameterTypeClass, new ArrayList<ParameterMapping>()).build());
    }
    
    // 设置结果映射
    if (resultMap != null) {
        String resultType = applyCurrentNamespace(resultMap, false);
        statementBuilder.resultMaps(
            configuration.getResultMap(resultType).build());
    }
    
    // 构建并注册 MappedStatement
    MappedStatement statement = statementBuilder.build();
    configuration.addMappedStatement(statement);
    return statement;
}

// 添加结果映射
public ResultMap addResultMap(String id, Class<?> type, 
        List<ResultMapping> resultMappings, Boolean autoMapping) {
    id = applyCurrentNamespace(id, false);
    // 构建 ResultMap 并注册
    ResultMap.Builder resultMapBuilder = new ResultMap.Builder(
        configuration, id, type, resultMappings, autoMapping);
    ResultMap resultMap = resultMapBuilder.build();
    configuration.addResultMap(resultMap);
    return resultMap;
}

// 应用命名空间
public String applyCurrentNamespace(String base, boolean isReference) {
    if (base == null) {
        return null;
    }
    if (isReference) {
        // 引用时检查是否已包含命名空间
        if (base.contains(".")) {
            return base;
        }
    } else {
        // 构建时添加当前命名空间
        if (base.startsWith(currentNamespace + ".")) {
            return base;
        }
        if (base.contains(".")) {
            throw new BuilderException("Dots are not allowed in element names, please remove it from " + base);
        }
    }
    return currentNamespace + "." + base;
}
```

## XMLScriptBuilder 详解

### 类概述

`XMLScriptBuilder` 负责解析 Mapper XML 文件中的动态 SQL 内容，将 `<where>`、`<if>`、`<foreach>` 等动态 SQL 标签转换为 `SqlNode` 树结构。该类是 MyBatis 动态 SQL 功能的核心实现，通过组合模式构建了一棵 `SqlNode` 树，每个 `SqlNode` 代表一种 SQL 处理逻辑（如条件判断、循环、文本等）。`XMLScriptBuilder` 使用 `NodeHandler` 模式处理不同类型的动态 SQL 标签，每种标签都有对应的 `NodeHandler` 实现。这种设计使得添加新的动态 SQL 标签变得非常容易，只需要实现新的 `NodeHandler` 并注册到 `nodeHandlerMap` 中即可。

### 核心字段与方法

```java
public class XMLScriptBuilder extends BaseBuilder {
    // 要解析的上下文节点
    private final XNode context;
    
    // 标记解析内容是否为动态 SQL
    private boolean isDynamic;
    
    // 参数类型
    private final Class<?> parameterType;
    
    // 节点处理器映射
    private final Map<String, NodeHandler> nodeHandlerMap = new HashMap<String, NodeHandler>();
    
    public XMLScriptBuilder(Configuration configuration, XNode context) {
        this(configuration, context, null);
    }
    
    public XMLScriptBuilder(Configuration configuration, XNode context, Class<?> parameterType) {
        super(configuration);
        this.context = context;
        this.parameterType = parameterType;
        initNodeHandlerMap();
    }
    
    // 初始化节点处理器映射
    private void initNodeHandlerMap() {
        nodeHandlerMap.put("trim", new TrimHandler());
        nodeHandlerMap.put("where", new WhereHandler());
        nodeHandlerMap.put("set", new SetHandler());
        nodeHandlerMap.put("foreach", new ForEachHandler());
        nodeHandlerMap.put("if", new IfHandler());
        nodeHandlerMap.put("choose", new ChooseHandler());
        nodeHandlerMap.put("when", new IfHandler());
        nodeHandlerMap.put("otherwise", new OtherwiseHandler());
        nodeHandlerMap.put("bind", new BindHandler());
    }
    
    // 解析 SQL 脚本节点
    public SqlSource parseScriptNode() {
        MixedSqlNode rootSqlNode = parseDynamicTags(context);
        SqlSource sqlSource = null;
        if (isDynamic) {
            sqlSource = new DynamicSqlSource(configuration, rootSqlNode);
        } else {
            sqlSource = new RawSqlSource(configuration, rootSqlNode, parameterType);
        }
        return sqlSource;
    }
    
    // 解析动态标签
    protected MixedSqlNode parseDynamicTags(XNode node) {
        List<SqlNode> contents = new ArrayList<SqlNode>();
        NodeList children = node.getNode().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            XNode child = node.newXNode(children.item(i));
            if (child.getNode().getNodeType() == Node.CDATA_SECTION_NODE || 
                child.getNode().getNodeType() == Node.TEXT_NODE) {
                String data = child.getStringBody("");
                TextSqlNode textSqlNode = new TextSqlNode(data);
                if (textSqlNode.isDynamic()) {
                    contents.add(textSqlNode);
                    isDynamic = true;
                } else {
                    contents.add(new StaticTextSqlNode(data));
                }
            } else if (child.getNode().getNodeType() == Node.ELEMENT_NODE) {
                String nodeName = child.getNode().getNodeName();
                NodeHandler handler = nodeHandlerMap.get(nodeName);
                if (handler == null) {
                    throw new BuilderException("Unknown element <" + nodeName + "> in SQL statement.");
                }
                handler.handleNode(child, contents);
                isDynamic = true;
            }
        }
        return new MixedSqlNode(contents);
    }
}
```

### 动态 SQL 标签处理器

`XMLScriptBuilder` 内部定义了一系列 `NodeHandler` 实现类，用于处理不同类型的动态 SQL 标签：

| 标签 | 处理器 | 功能描述 |
|------|-------|---------|
| `<trim>` | TrimHandler | 自定义trim元素，可以完成where或者set标签的功能 |
| `<where>` | WhereHandler | 智能处理WHERE子句，自动处理多余的AND/OR |
| `<set>` | SetHandler | 智能处理SET子句，自动处理多余的逗号 |
| `<foreach>` | ForEachHandler | 遍历集合，用于IN查询和批量操作 |
| `<if>` | IfHandler | 条件判断，根据表达式的值决定是否包含SQL片段 |
| `<choose>` | ChooseHandler | 选择结构，类似Java的switch语句 |
| `<when>` | IfHandler | choose标签的分支判断 |
| `<otherwise>` | OtherwiseHandler | choose标签的默认分支 |
| `<bind>` | BindHandler | 创建变量并绑定到上下文 |

## SqlSourceBuilder 详解

### 类概述

`SqlSourceBuilder` 负责解析 SQL 语句中的 `#{}` 占位符，将其转换为 `StaticSqlSource` 对象。该类使用 `GenericTokenParser` 来识别 `#{}` 格式的参数占位符，并通过 `ParameterMappingTokenHandler` 将占位符转换为 `ParameterMapping` 对象列表。最终，SQL 语句中的占位符被替换为 `?`（JDBC 标准占位符），同时生成完整的参数映射信息。`SqlSourceBuilder` 是 MyBatis 参数绑定的核心组件，它确保了 SQL 语句的正确性和参数映射的完整性。

### 核心方法

```java
public class SqlSourceBuilder extends BaseBuilder {
    private static final String parameterProperties = 
        "javaType,jdbcType,mode,numericScale,resultMap,typeHandler,jdbcTypeName";
    
    public SqlSourceBuilder(Configuration configuration) {
        super(configuration);
    }
    
    public SqlSource parse(String originalSql, Class<?> parameterType, 
            Map<String, Object> additionalParameters) {
        ParameterMappingTokenHandler handler = new ParameterMappingTokenHandler(
            configuration, parameterType, additionalParameters);
        GenericTokenParser parser = new GenericTokenParser("#{", "}", handler);
        String sql = parser.parse(originalSql);
        return new StaticSqlSource(configuration, sql, handler.getParameterMappings());
    }
    
    private static class ParameterMappingTokenHandler extends BaseBuilder implements TokenHandler {
        private List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
        private Class<?> parameterType;
        private MetaObject metaParameters;
        
        public ParameterMappingTokenHandler(Configuration configuration, 
                Class<?> parameterType, Map<String, Object> additionalParameters) {
            super(configuration);
            this.parameterType = parameterType;
            this.metaParameters = configuration.newMetaObject(additionalParameters);
        }
        
        @Override
        public String handleToken(String content) {
            parameterMappings.add(buildParameterMapping(content));
            return "?";
        }
        
        private ParameterMapping buildParameterMapping(String content) {
            Map<String, String> propertiesMap = parseParameterMapping(content);
            String property = propertiesMap.get("property");
            Class<?> propertyType;
            
            // 确定属性类型
            if (metaParameters.hasGetter(property)) {
                propertyType = metaParameters.getGetterType(property);
            } else if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
                propertyType = parameterType;
            } else if (JdbcType.CURSOR.name().equals(propertiesMap.get("jdbcType"))) {
                propertyType = java.sql.ResultSet.class;
            } else if (property == null || Map.class.isAssignableFrom(parameterType)) {
                propertyType = Object.class;
            } else {
                MetaClass metaClass = MetaClass.forClass(parameterType, 
                    configuration.getReflectorFactory());
                if (metaClass.hasGetter(property)) {
                    propertyType = metaClass.getGetterType(property);
                } else {
                    propertyType = Object.class;
                }
            }
            
            // 构建 ParameterMapping
            ParameterMapping.Builder builder = new ParameterMapping.Builder(
                configuration, property, propertyType);
            
            // 设置其他属性
            for (Map.Entry<String, String> entry : propertiesMap.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if ("javaType".equals(name)) {
                    javaType = resolveClass(value);
                    builder.javaType(javaType);
                } else if ("jdbcType".equals(name)) {
                    builder.jdbcType(resolveJdbcType(value));
                } else if ("mode".equals(name)) {
                    builder.mode(resolveParameterMode(value));
                } else if ("numericScale".equals(name)) {
                    builder.numericScale(integerValueOf(value, null));
                } else if ("resultMap".equals(name)) {
                    builder.resultMap(value);
                } else if ("typeHandler".equals(name)) {
                    builder.typeHandler(resolveTypeHandler(propertyType, value));
                } else if ("jdbcTypeName".equals(name)) {
                    builder.jdbcTypeName(value);
                }
            }
            return builder.build();
        }
    }
}
```

## 相关构建器详解

### SqlSessionFactoryBuilder

`SqlSessionFactoryBuilder` 是整个 MyBatis 初始化的入口类，负责构建 `SqlSessionFactory` 实例。它提供了多个重载的 `build()` 方法，支持从 Reader、InputStream 或配置对象创建 `SqlSessionFactory`。该类内部使用 `XMLConfigBuilder` 解析配置文件，生成 `Configuration` 对象，然后创建 `DefaultSqlSessionFactory`。

```java
public SqlSessionFactory build(Reader reader, String environment, Properties properties) {
    try {
        XMLConfigBuilder parser = new XMLConfigBuilder(reader, environment, properties);
        Configuration configuration = parser.parse();
        return build(configuration);
    } catch (Exception e) {
        throw ExceptionFactory.wrapException("Error building SqlSession.", e);
    } finally {
        ErrorContext.instance().reset();
        try {
            reader.close();
        } catch (IOException e) {
            // 忽略关闭异常
        }
    }
}

public SqlSessionFactory build(Configuration configuration) {
    return new DefaultSqlSessionFactory(configuration);
}
```

### CacheBuilder

`CacheBuilder` 负责构建缓存对象，采用流式 API 设计，通过链式调用设置各种缓存属性。`CacheBuilder` 使用建造者模式，支持自定义缓存实现类和装饰器类，可以灵活配置缓存的各种特性（如大小、清除间隔、是否可读写等）。

```java
public Cache build() {
    setDefaultImplementations();
    Cache cache = newBaseCacheInstance(implementation, id);
    setCacheProperties(cache);
    
    // 只对内置缓存应用装饰器
    if (PerpetualCache.class.equals(cache.getClass())) {
        for (Class<? extends Cache> decorator : decorators) {
            cache = newCacheDecoratorInstance(decorator, cache);
            setCacheProperties(cache);
        }
        cache = newSerializedCache(SerializedCache.class, cache);
        cache = newLruCache(cache);
        cache = newScheduledCache(cache);
    }
    
    // 设置阻塞标志
    if (blocking) {
        cache = new BlockingCache(cache);
    }
    
    return cache;
}
```

### MapperAnnotationBuilder

`MapperAnnotationBuilder` 负责解析 Mapper 接口中的注解，将注解配置转换为 MyBatis 内部配置对象。它支持解析的注解包括 `@Select`、`@Insert`、`@Update`、`@Delete`、`@Results`、`@ResultMap`、`@SelectProvider` 等。`MapperAnnotationBuilder` 在内部使用 `MapperBuilderAssistant` 执行实际的构建操作。

```java
public class MapperAnnotationBuilder {
    private final Set<Class<? extends Annotation>> sqlAnnotationTypes = 
        new HashSet<Class<? extends Annotation>>();
    private final Set<Class<? extends Annotation>> sqlProviderAnnotationTypes = 
        new HashSet<Class<? extends Annotation>>();
    
    private final Configuration configuration;
    private final MapperBuilderAssistant builderAssistant;
    private final Class<?> type;
    
    public void parse() {
        String resource = type.toString();
        if (!configuration.isResourceLoaded(resource)) {
            configuration.addLoadedResource(resource);
            builderAssistant.setCurrentNamespace(type.getName());
            
            // 解析方法上的注解
            for (Method method : type.getMethods()) {
                if (!method.isBridge()) {
                    parseStatement(method);
                }
            }
        }
    }
}
```

## 配置解析完整流程

### 初始化流程图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         MyBatis 初始化流程                                    │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  1. 创建 SqlSessionFactoryBuilder                                            │
│     SqlSessionFactoryBuilder builder = new SqlSessionFactoryBuilder();      │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  2. 读取配置文件                                                              │
│     Reader reader = Resources.getResourceAsReader("mybatis-config.xml");   │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  3. 构建 SqlSessionFactory（内部创建 XMLConfigBuilder）                       │
│     SqlSessionFactory factory = builder.build(reader);                     │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
            ┌───────────────────────┴───────────────────────┐
            ▼                                               ▼
    ┌──────────────────────┐                   ┌──────────────────────┐
    │ XMLConfigBuilder     │                   │  Configuration       │
    │ parseConfiguration() │                   │  对象创建            │
    └──────────────────────┘                   └──────────────────────┘
            │                                               │
            ▼                                               │
    ┌──────────────────────────────────────────────────────────────┐
    │  按顺序解析配置元素：                                            │
    │  properties → settings → typeAliases → plugins →              │
    │  objectFactory → objectWrapperFactory → reflectorFactory →    │
    │  settings(applied) → environments → databaseIdProvider →      │
    │  typeHandlers → mappers                                       │
    └──────────────────────────────────────────────────────────────┘
            │
            ▼
    ┌──────────────────────────────────────────────────────────────┐
    │  解析 Mapper 文件（XMLMapperBuilder）                          │
    │  - 解析 <cache> 和 <cache-ref>                                │
    │  - 解析 <parameterMap>（已废弃）                               │
    │  - 解析 <resultMap>                                           │
    │  - 解析 <select>/<insert>/<update>/<delete>                   │
    └──────────────────────────────────────────────────────────────┘
            │
            ▼
    ┌──────────────────────────────────────────────────────────────┐
    │  解析 SQL 语句（XMLStatementBuilder）                          │
    │  - 解析语句属性（id、parameterType、resultType 等）            │
    │  - 处理 <include> 片段引用                                     │
    │  - 处理 <selectKey>（自增ID获取）                              │
    │  - 解析动态 SQL（XMLScriptBuilder）                            │
    │  - 构建 MappedStatement                                       │
    └──────────────────────────────────────────────────────────────┘
            │
            ▼
    ┌──────────────────────────────────────────────────────────────┐
    │  返回 SqlSessionFactory                                        │
    │  SqlSessionFactory factory = new DefaultSqlSessionFactory(    │
    │      configuration);                                          │
    └──────────────────────────────────────────────────────────────┘
```

### Builder 协作关系

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Builder 协作关系图                                  │
└─────────────────────────────────────────────────────────────────────────────┘

SqlSessionFactoryBuilder（入口）
        │
        ▼
XMLConfigBuilder.parse()
        │
        ├──► propertiesElement()     ──► 配置解析
        ├──► settingsAsProperties()  ──► 配置解析
        ├──► typeAliasesElement()    ──► 配置解析
        ├──► pluginElement()         ──► 配置解析
        ├──► objectFactoryElement()  ──► 配置解析
        ├──► environmentsElement()   ──► 配置解析
        ├──► typeHandlerElement()    ──► 配置解析
        │
        └──► mapperElement()         ──► 加载 Mapper
                │
                ▼
        XMLMapperBuilder.parse()
                │
                ├──► parseCache()        ──► CacheBuilder
                │         │
                │         ▼
                │     Cache 对象
                │
                ├──► parseCacheRef()
                │
                ├──► parseResultMaps()   ──► ResultMapResolver
                │         │
                │         ▼
                │     ResultMap 对象
                │
                └──► parseStatements()   ──► XMLStatementBuilder
                        │
                        ├──► parseStatementNode()
                        │       │
                        │       ├──► XMLIncludeTransformer.applyIncludes()
                        │       │           │
                        │       │           ▼
                        │       │       处理 <include> 标签
                        │       │
                        │       ├──► processSelectKeyNodes()
                        │       │
                        │       └──► langDriver.createSqlSource()
                        │                   │
                        │                   ▼
                        │           XMLScriptBuilder.parseScriptNode()
                        │                   │
                        │                   ├──► parseDynamicTags()
                        │                   │       │
                        │                   │       ├──► TextSqlNode
                        │                   │       ├──► StaticTextSqlNode
                        │                   │       └──► NodeHandler（处理动态标签）
                        │                   │
                        │                   ▼
                        │           SqlSource 对象
                        │
                        └──► builderAssistant.addMappedStatement()
                                    │
                                    ▼
                            MappedStatement 对象
```

## 设计模式应用

### 模板方法模式

`BaseBuilder` 作为抽象基类定义了通用的类型解析流程，而将具体的解析逻辑延迟到子类中实现。`parseConfiguration()` 方法定义了解析的标准流程模板，各个子类按照这个模板执行具体的解析操作。这种设计使得整个 Builder 体系具有统一的解析流程，同时又保持了各个 Builder 类的灵活性。

### 建造者模式

`SqlSessionFactoryBuilder`、`CacheBuilder`、`MappedStatement.Builder` 等类采用建造者模式，通过链式调用设置对象的各个属性，最后调用 `build()` 方法生成最终对象。这种模式使得对象的构建过程更加清晰易读，也支持逐步构建和验证。

### 组合模式

`XMLScriptBuilder` 中的 `SqlNode` 体系采用组合模式，`MixedSqlNode` 作为组合节点可以包含多个 `SqlNode`（包括其他 `MixedSqlNode`），而叶子节点如 `TextSqlNode`、`StaticTextSqlNode` 则代表具体的 SQL 内容。这种设计使得动态 SQL 可以嵌套任意深度，结构清晰。

### 策略模式

`CacheBuilder` 中的缓存装饰器链体现了策略模式的思想，不同的装饰器类（`LruCache`、`ScheduledCache`、`BlockingCache` 等）可以在运行时组合，每种装饰器都实现了相同的 `Cache` 接口，可以透明地添加和移除。

### 责任链模式

`XMLScriptBuilder` 中的 `NodeHandler` 处理机制可以看作是一种简化版的责任链。每个 `NodeHandler` 负责处理特定类型的节点，如果当前处理器无法处理，则可以传递给其他处理器。虽然 MyBatis 使用映射表直接定位处理器，但这种设计思想与责任链模式是相通的。

## 核心要点总结

| 主题 | 关键点 |
|------|-------|
| BaseBuilder | 抽象基类，提供类型解析和实例创建的通用方法 |
| XMLConfigBuilder | 解析主配置文件，入口点，解析顺序严格 |
| XMLMapperBuilder | 解析 Mapper 映射文件，管理 SQL 片段 |
| XMLStatementBuilder | 解析单个 SQL 语句节点，构建 MappedStatement |
| MapperBuilderAssistant | 提供构建操作的辅助工具类 |
| XMLScriptBuilder | 解析动态 SQL，构建 SqlNode 树 |
| SqlSourceBuilder | 解析 #{} 占位符，构建 StaticSqlSource |
| 解析顺序 | properties 最先，mappers 最后，中间按依赖顺序 |
| 设计模式 | 模板方法、建造者、组合、策略模式的应用 |

## 参考资料

- MyBatis 官方文档：配置解析章节
- 源码注释中的 issue 引用（issue #117、#352、#631、#676、#746 等）
- 相关类的 Javadoc 文档
