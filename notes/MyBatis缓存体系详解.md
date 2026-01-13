# MyBatis 缓存体系详解

## 一、概述

MyBatis 提供了功能强大的缓存机制，用于提升数据库查询性能。缓存机制采用了经典的二级缓存架构设计，将缓存分为一级缓存和二级缓存两个层次，分别针对不同的作用域进行管理。一级缓存作用于 SqlSession 级别，是 SqlSession 内部维护的本地缓存；二级缓存作用于 Mapper 命名空间级别，是跨 SqlSession 共享的全局缓存。MyBatis 的缓存实现采用了装饰器模式，通过层层包装的方式为缓存添加各种功能特性，如淘汰策略、序列化、事务支持等。这种设计使得缓存系统具有良好的扩展性和灵活性，可以根据实际需求灵活组合不同的缓存装饰器。

### 1.1 缓存的核心价值

在应用程序中，数据库访问往往是性能瓶颈之一。频繁的数据库查询不仅增加了数据库服务器的负载，还延长了应用程序的响应时间。MyBatis 的缓存机制通过将查询结果暂存在内存中，使得相同条件的后续查询可以直接从内存中获取数据，而无需再次访问数据库。这种内存访问的速度远高于磁盘访问，能够显著提升查询性能。合理的缓存配置可以将查询性能提升数倍甚至数十倍，对于读多写少的应用场景效果尤为明显。同时，缓存机制还能够减轻数据库的压力，提高系统的整体吞吐量和可用性。

### 1.2 缓存的整体架构

MyBatis 的缓存体系采用了分层的架构设计，从底层到高层依次为：基础存储实现层、功能增强装饰层和缓存管理层。基础存储实现层以 PerpetualCache 为核心，提供了最基本的键值存储功能。功能增强装饰层通过装饰器模式，为缓存添加了淘汰策略、序列化支持、事务缓冲、日志记录等功能。缓存管理层则负责缓存的创建、配置和管理，包括 CacheBuilder 和 TransactionalCacheManager 等组件。这种分层设计使得每个层次都有明确的职责划分，既保证了代码的清晰性，又提高了系统的可维护性和可扩展性。

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          MyBatis 缓存架构                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                      缓存管理层                                   │   │
│  │  ┌──────────────────────┐  ┌──────────────────────────────────┐  │   │
│  │  │  TransactionalCache  │  │     CacheBuilder                  │  │   │
│  │  │      Manager         │  │     (缓存构建器)                   │  │   │
│  │  └──────────────────────┘  └──────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                              ↓                                          │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                      装饰器层                                     │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐    │   │
│  │  │Blocking │ │ Logging │ │Serialized│ │Scheduled│ │Synchronized│  │   │
│  Cache   │  │ │ │  Cache  │ │  Cache   │ │  Cache  │ │  Cache   │  │   │
│  │  └─────────┘ └─────────┘ └─────────┘ └─────────┘ └─────────┘    │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                              ↓                                          │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                      淘汰策略层                                   │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐    │   │
│  │  │  Lru    │ │  Fifo   │ │  Weak    │ │  Soft   │ │Scheduled│   │   │
│  │  │  Cache  │ │  Cache  │ │  Cache   │ │  Cache  │ │  Cache  │   │   │
│  │  └─────────┘ └─────────┘ └─────────┘ └─────────┘ └─────────┘    │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                              ↓                                          │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                      基础存储层                                   │   │
│  │  ┌───────────────────────────────────────────────────────────┐  │   │
│  │  │                    PerpetualCache                          │  │   │
│  │  │             (基于 HashMap 的永久缓存实现)                    │  │   │
│  │  └───────────────────────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

## 二、一级缓存机制

### 2.1 一级缓存的定义与特点

一级缓存是 MyBatis 默认启用的缓存机制，它作用于 SqlSession 级别，即每个 SqlSession 都有自己独立的一级缓存空间。一级缓存的生命周期与 SqlSession 相同，当 SqlSession 被创建时，一级缓存也被创建；当 SqlSession 被关闭或清空时，一级缓存也会被清除。一级缓存存储的是查询结果对象，而非序列化后的数据，因此读取速度非常快。一级缓存的最大特点是作用域最小、速度最快，但共享范围也最有限，仅在同一个 SqlSession 内部有效。

一级缓存的设计理念是减少同一 SqlSession 内对相同查询的重复执行。当执行查询操作时，MyBatis 会首先检查一级缓存中是否存在相同查询的结果，如果存在则直接返回缓存结果；如果不存在则执行数据库查询，并将查询结果存入一级缓存。这种机制在同一个 SqlSession 执行多次相同查询时能够显著提升性能，避免了对数据库的重复访问。

### 2.2 一级缓存的作用域配置

MyBatis 提供了一级缓存作用域的配置选项，通过 LocalCacheScope 枚举类进行控制。一级缓存有两种作用域模式：SESSION 模式和 STATEMENT 模式。当设置为 SESSION 模式时，一级缓存在整个 SqlSession 生命周期内有效，同一个 SqlSession 执行的所有查询都可以共享这个缓存。当设置为 STATEMENT 模式时，一级缓存仅在当前语句执行期间有效，执行完成后立即清除，这种模式类似于禁用一级缓存，但开销更低。

```java
// 一级缓存作用域配置
public enum LocalCacheScope {
  SESSION,    // 会话级别（默认），整个 SqlSession 生命周期内有效
  STATEMENT   // 语句级别，仅当前语句有效
}
```

在 MyBatis 配置文件中，可以通过 setting 元素配置一级缓存的作用域：

```xml
<configuration>
  <settings>
    <!-- 配置一级缓存作用域 -->
    <setting name="localCacheScope" value="SESSION"/>
  </settings>
</configuration>
```

### 2.3 一级缓存的生命周期

一级缓存的生命周期管理遵循严格的边界控制。缓存的创建发生在 SqlSession 实例化时，此时会创建基础的缓存数据结构。缓存的存续期间与 SqlSession 完全一致，在此期间执行的所有查询操作都会使用同一份缓存数据。当 SqlSession 执行 commit 或 rollback 操作时，一级缓存会被清空，以确保事务的一致性。最后，当 SqlSession 被关闭时，一级缓存也会被彻底销毁，释放占用的内存资源。

```java
// 一级缓存生命周期示例
SqlSession sqlSession = sqlSessionFactory.openSession();
try {
  // 此时创建一级缓存
  User user1 = sqlSession.selectOne("com.mapper.UserMapper.selectById", 1);
  // 从一级缓存获取（不访问数据库）
  User user2 = sqlSession.selectOne("com.mapper.UserMapper.selectById", 1);
  
  sqlSession.commit();  // 提交事务，清空一级缓存
  // 此时一级缓存已被清空
  
} finally {
  sqlSession.close();   // 关闭 SqlSession，销毁一级缓存
}
```

### 2.4 一级缓存的失效场景

一级缓存在以下几种情况下会被清空或失效：首先，当 SqlSession 执行 insert、update、delete 操作并提交事务时，一级缓存会被自动清空，以确保缓存数据与数据库数据的一致性。其次，当 SqlSession 执行 rollback 操作时，一级缓存也会被清空。第三，当 SqlSession 调用 clearCache 方法时，会主动清空一级缓存。第四，使用不同的 SqlStatement ID 执行查询时，即使参数相同也不会使用同一份缓存，因为缓存的 key 包含了 statementId。此外，如果两次查询之间执行了任何写操作，一级缓存也会失效。

```java
// 一级缓存失效示例
SqlSession sqlSession = sqlSessionFactory.openSession();
try {
  // 第一次查询，存入一级缓存
  User user1 = sqlSession.selectOne("com.mapper.UserMapper.selectById", 1);
  
  // 执行更新操作
  User user = new User();
  user.setId(1);
  user.setName("newName");
  sqlSession.update("com.mapper.UserMapper.update", user);
  sqlSession.commit();  // 提交后一级缓存失效
  
  // 第二次查询，需要重新访问数据库
  User user2 = sqlSession.selectOne("com.mapper.UserMapper.selectById", 1);
  
} finally {
  sqlSession.close();
}
```

## 三、二级缓存机制

### 3.1 二级缓存的定义与特点

二级缓存是 MyBatis 缓存体系中的全局缓存，作用于 Mapper 命名空间级别。与一级缓存不同，二级缓存是跨 SqlSession 共享的，多个 SqlSession 可以访问同一份二级缓存数据。二级缓存的生命周期与 Mapper 命名空间关联，只要命名空间存在，二级缓存就会一直存在。二级缓存的存储单位是命名空间，每个 Mapper 接口或 XML 文件对应一个独立的二级缓存空间。二级缓存默认是关闭的，需要显式配置才能启用。

二级缓存的设计目标是实现跨 SqlSession 的数据共享，提升系统整体的数据访问效率。当多个用户或请求需要访问相同的数据时，二级缓存能够显著减少数据库的访问压力，提高系统的响应速度。同时，二级缓存也支持更丰富的配置选项，如淘汰策略、序列化方式、刷新间隔等，可以根据实际需求进行灵活配置。

### 3.2 二级缓存的配置方式

二级缓存的配置可以在 XML 映射文件或注解中进行，也可以在全局配置文件中进行全局设置。在 XML 映射文件中，通过 cache 元素启用并配置二级缓存；在注解方式中，使用 @CacheNamespace 注解启用缓存。配置时需要指定缓存的实现类、大小、淘汰策略等参数。对于需要共享缓存的 Mapper，可以使用 cache-ref 元素或 @CacheNamespaceRef 注解引用其他命名空间的缓存。

```xml
<!-- XML 配置方式 -->
<mapper namespace="com.mapper.UserMapper">
  <!-- 启用二级缓存 -->
  <cache
    eviction="LRU"
    flushInterval="60000"
    size="512"
    readWrite="true"/>
  
  <!-- 或引用其他命名空间的缓存 -->
  <cache-ref namespace="com.mapper.BaseMapper"/>
</mapper>
```

```java
// 注解配置方式
@CacheNamespace(
  eviction = LruCache.class,
  flushInterval = 60000,
  size = 512,
  readWrite = true
)
public interface UserMapper {
  @Select("SELECT * FROM user WHERE id = #{id}")
  User selectById(Integer id);
}
```

### 3.3 二级缓存的工作原理

二级缓存的工作原理涉及多个组件的协作。当执行查询操作时，MyBatis 首先检查一级缓存，如果命中则直接返回；否则继续检查二级缓存。如果二级缓存命中，则将结果存入一级缓存后返回；如果仍未命中，则执行数据库查询，将结果存入一级缓存，同时根据配置决定是否存入二级缓存。写入操作时，MyBatis 会同时更新一级缓存和二级缓存，以确保数据一致性。

```java
// 二级缓存工作流程伪代码
public class CachingExecutor implements Executor {
  private Executor delegate;
  private TransactionalCacheManager tcm = new TransactionalCacheManager();
  
  @Override
  public <E> List<E> query(MappedStatement ms, Object parameter, 
                           RowBounds rowBounds, ResultHandler handler) {
    // 1. 生成缓存 key
    CacheKey key = createCacheKey(ms, parameter, rowBounds);
    Cache cache = ms.getCache();
    
    // 2. 检查二级缓存
    if (cache != null) {
      // 从 TransactionalCache 获取
      Object value = tcm.getObject(cache, key);
      if (value != null) {
        return (List<E>) value;
      }
    }
    
    // 3. 委托给基础执行器执行查询
    List<E> result = delegate.query(ms, parameter, rowBounds, handler);
    
    // 4. 将结果存入二级缓存
    if (cache != null) {
      tcm.putObject(cache, key, result);
    }
    
    return result;
  }
}
```

### 3.4 二级缓存的事务管理

二级缓存的事务管理是通过 TransactionalCache 和 TransactionalCacheManager 两个类实现的。由于二级缓存可能被多个 SqlSession 共享，事务管理变得尤为重要。TransactionalCache 采用了一种延迟写入的策略：在事务执行期间，新的缓存数据被写入到事务缓冲区（entriesToAddOnCommit），而不是直接写入二级缓存。只有当事务提交时，缓冲区中的数据才会被刷新到实际的二级缓存中；如果事务回滚，缓冲区中的数据则被丢弃。

```java
// 事务性缓存的核心逻辑
public class TransactionalCache implements Cache {
  private final Cache delegate;
  private boolean clearOnCommit;  // 是否在提交时清空
  private final Map<Object, Object> entriesToAddOnCommit;  // 待提交的数据
  private final Set<Object> entriesMissedInCache;  // 缓存未命中的 key
  
  @Override
  public Object getObject(Object key) {
    // 从实际缓存获取
    Object object = delegate.getObject(key);
    if (object == null) {
      // 记录缓存未命中
      entriesMissedInCache.add(key);
    }
    // 如果即将提交时清空，返回 null
    if (clearOnCommit) {
      return null;
    }
    return object;
  }
  
  @Override
  public void putObject(Object key, Object object) {
    // 仅写入事务缓冲区，不直接写入实际缓存
    entriesToAddOnCommit.put(key, object);
  }
  
  public void commit() {
    if (clearOnCommit) {
      delegate.clear();  // 清空实际缓存
    }
    // 将缓冲区数据刷新到实际缓存
    flushPendingEntries();
    reset();
  }
  
  public void rollback() {
    // 解锁缓存未命中的条目
    unlockMissedEntries();
    reset();
  }
}
```

### 3.5 缓存的刷新策略

二级缓存提供了灵活的刷新策略配置。通过 flushInterval 属性可以设置自动刷新间隔，缓存会在指定时间间隔后自动清空，强制下次查询从数据库重新加载数据。这个功能对于数据变化频率较高的场景非常有用，可以避免缓存数据与数据库数据不一致的问题。此外，通过在 XML 中使用 flushCache 属性，可以控制单个查询是否在执行后刷新缓存。

```xml
<!-- 缓存刷新配置示例 -->
<cache
  eviction="LRU"
  flushInterval="300000"    <!-- 5分钟自动刷新 -->
  size="1024"
  readWrite="true"/>

<!-- 单个查询的刷新配置 -->
<select id="selectById" parameterType="int" 
        useCache="true"       <!-- 是否使用二级缓存 -->
        flushCache="false">   <!-- 执行后是否刷新缓存 -->
  SELECT * FROM user WHERE id = #{id}
</select>

<insert id="insert" flushCache="true">  <!-- 执行后刷新缓存 -->
  INSERT INTO user(name) VALUES(#{name})
</insert>
```

## 四、核心接口与类

### 4.1 Cache 接口详解

Cache 接口是 MyBatis 缓存体系的核心接口，定义了缓存的基本操作契约。该接口采用 SPI（Service Provider Interface）机制，允许开发者自定义缓存实现。每个命名空间对应一个 Cache 实例，缓存实例通过命名空间 ID 进行标识。Cache 接口定义了六个核心方法：getId() 返回缓存的唯一标识；putObject() 存储缓存条目；getObject() 获取缓存条目；removeObject() 移除缓存条目；clear() 清空整个缓存；getSize() 返回缓存中条目数量。

```java
// Cache 接口定义
public interface Cache {
  /**
   * 获取缓存的唯一标识
   * @return 缓存ID，通常为 Mapper 命名空间
   */
  String getId();
  
  /**
   * 存储缓存条目
   * @param key 缓存键，通常为 CacheKey 对象
   * @param value 缓存值，查询结果对象
   */
  void putObject(Object key, Object value);
  
  /**
   * 获取缓存条目
   * @param key 缓存键
   * @return 缓存值，如果不存在则返回 null
   */
  Object getObject(Object key);
  
  /**
   * 移除缓存条目
   * @param key 缓存键
   * @return 被移除的值
   */
  Object removeObject(Object key);
  
  /**
   * 清空整个缓存
   */
  void clear();
  
  /**
   * 获取缓存中的条目数量
   * @return 缓存条目数量
   */
  int getSize();
  
  /**
   * 获取读写锁（可选，3.2.6 后不再由核心调用）
   * @return 读写锁对象
   */
  ReadWriteLock getReadWriteLock();
}
```

### 4.2 PerpetualCache 永久缓存

PerpetualCache 是 MyBatis 缓存体系中最基础的缓存实现类，提供了最简单、最纯粹的键值存储功能。它内部使用 HashMap 作为数据存储结构，不包含任何淘汰策略或特殊功能。PerpetualCache 的设计理念是作为缓存体系的底层基础，其他所有功能都通过装饰器模式添加到其上。这种设计使得缓存系统具有良好的模块化特性，每个装饰器只负责单一的功能增强。

```java
// PerpetualCache 实现
public class PerpetualCache implements Cache {
  private final String id;  // 缓存标识
  private final Map<Object, Object> cache = new HashMap<Object, Object>();
  
  public PerpetualCache(String id) {
    this.id = id;
  }
  
  @Override
  public String getId() {
    return id;
  }
  
  @Override
  public int getSize() {
    return cache.size();  // 返回缓存条目数量
  }
  
  @Override
  public void putObject(Object key, Object value) {
    cache.put(key, value);  // 存储键值对
  }
  
  @Override
  public Object getObject(Object key) {
    return cache.get(key);  // 根据键获取值
  }
  
  @Override
  public Object removeObject(Object key) {
    return cache.remove(key);  // 移除键值对
  }
  
  @Override
  public void clear() {
    cache.clear();  // 清空缓存
  }
  
  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;  // 不提供锁，由装饰器负责
  }
}
```

### 4.3 CacheKey 缓存键

CacheKey 是 MyBatis 用于生成缓存键的核心类，它将多个影响查询结果的因素组合成一个唯一的键值。CacheKey 的设计考虑了查询语句、参数值、分页信息等多个维度，确保即使只有参数值的微小变化，也会生成不同的缓存键。CacheKey 采用了复合哈希算法，综合了哈希码、校验和、计数和更新列表等信息，保证了键的唯一性和分布均匀性。

```java
// CacheKey 实现详解
public class CacheKey implements Cloneable, Serializable {
  private static final long serialVersionUID = 1146682552656046210L;
  
  private final int multiplier;      // 乘数因子，用于哈希计算
  private int hashcode;              // 复合哈希码
  private long checksum;             // 校验和
  private int count;                 // 更新次数
  private final List<Object> updateList;  // 更新列表，记录所有参与哈希计算的因子
  
  // 默认初始值
  private static final int DEFAULT_MULTIPLYER = 37;
  private static final int DEFAULT_HASHCODE = 17;
  
  public CacheKey() {
    this.hashcode = DEFAULT_HASHCODE;
    this.multiplier = DEFAULT_MULTIPLYER;
    this.count = 0;
    this.updateList = new ArrayList<Object>();
  }
  
  // 通过 update 方法将因子加入 CacheKey
  public void update(Object object) {
    int baseHashCode = object == null ? 1 : ArrayUtil.hashCode(object);
    
    count++;                          // 更新计数
    checksum += baseHashCode;         // 更新校验和
    baseHashCode *= count;            // 乘以计数因子
    
    hashcode = multiplier * hashcode + baseHashCode;  // 更新哈希码
    
    updateList.add(object);           // 记录更新因子
  }
  
  // 更新所有因子
  public void updateAll(Object[] objects) {
    for (Object o : objects) {
      update(o);
    }
  }
  
  @Override
  public boolean equals(Object object) {
    if (this == object) return true;
    if (!(object instanceof CacheKey)) return false;
    
    CacheKey cacheKey = (CacheKey) object;
    
    // 比较多个维度确保唯一性
    if (hashcode != cacheKey.hashcode) return false;
    if (checksum != cacheKey.checksum) return false;
    if (count != cacheKey.count) return false;
    
    // 逐个比较更新列表中的因子
    for (int i = 0; i < updateList.size(); i++) {
      Object thisObject = updateList.get(i);
      Object thatObject = cacheKey.updateList.get(i);
      if (!ArrayUtil.equals(thisObject, thatObject)) {
        return false;
      }
    }
    return true;
  }
  
  @Override
  public int hashCode() {
    return hashcode;
  }
  
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(hashcode).append(':').append(checksum);
    for (Object object : updateList) {
      sb.append(':').append(ArrayUtil.toString(object));
    }
    return sb.toString();
  }
}
```

CacheKey 的生成过程会综合考虑以下因素：MappedStatement 的 ID（唯一标识查询语句）、查询参数值、分页信息（RowBounds）、Configuration 对象等。这种设计确保了缓存键的唯一性，使得相同的查询能够命中缓存，而不同的查询则不会相互干扰。

### 4.4 CacheBuilder 缓存构建器

CacheBuilder 是 MyBatis 用于构建缓存实例的建造者类，它封装了缓存创建的复杂逻辑，包括设置默认实现、添加装饰器、配置属性等。CacheBuilder 采用了流式接口设计，支持链式调用，使得缓存配置更加简洁和直观。CacheBuilder 在构建缓存时会按照特定的顺序添加装饰器，确保缓存功能的正确组合。

```java
// CacheBuilder 缓存构建器
public class CacheBuilder {
  private final String id;  // 缓存标识
  private Class<? extends Cache> implementation;  // 缓存实现类
  private final List<Class<? extends Cache>> decorators;  // 装饰器列表
  private Integer size;              // 缓存大小
  private Long clearInterval;        // 刷新间隔（毫秒）
  private boolean readWrite;         // 是否支持序列化
  private Properties properties;     // 扩展属性
  private boolean blocking;          // 是否启用阻塞
  
  public Cache build() {
    // 1. 设置默认实现类
    setDefaultImplementations();
    
    // 2. 创建基础缓存实例
    Cache cache = newBaseCacheInstance(implementation, id);
    
    // 3. 设置缓存属性
    setCacheProperties(cache);
    
    // 4. 添加标准装饰器（仅对 PerpetualCache）
    if (PerpetualCache.class.equals(cache.getClass())) {
      for (Class<? extends Cache> decorator : decorators) {
        cache = newCacheDecoratorInstance(decorator, cache);
        setCacheProperties(cache);
      }
      cache = setStandardDecorators(cache);
    } else if (!LoggingCache.class.isAssignableFrom(cache.getClass())) {
      cache = new LoggingCache(cache);
    }
    
    return cache;
  }
  
  // 设置标准装饰器组合
  private Cache setStandardDecorators(Cache cache) {
    try {
      MetaObject metaCache = SystemMetaObject.forObject(cache);
      
      // 设置缓存大小
      if (size != null && metaCache.hasSetter("size")) {
        metaCache.setValue("size", size);
      }
      
      // 添加定时刷新装饰器
      if (clearInterval != null) {
        cache = new ScheduledCache(cache);
        ((ScheduledCache) cache).setClearInterval(clearInterval);
      }
      
      // 添加序列化装饰器
      if (readWrite) {
        cache = new SerializedCache(cache);
      }
      
      // 添加日志装饰器
      cache = new LoggingCache(cache);
      
      // 添加同步装饰器
      cache = new SynchronizedCache(cache);
      
      // 添加阻塞装饰器
      if (blocking) {
        cache = new BlockingCache(cache);
      }
      
      return cache;
    } catch (Exception e) {
      throw new CacheException("Error building standard cache decorators.", e);
    }
  }
}
```

## 五、缓存装饰器详解

### 5.1 LruCache 最近最少使用缓存

LruCache（Least Recently Used Cache）是最常用的缓存淘汰策略装饰器，它基于最近最少使用算法管理缓存空间。当缓存达到指定大小时，如果需要添加新的条目，系统会自动移除最近最少使用的条目。LruCache 内部使用 LinkedHashMap 实现，通过访问顺序排序确保最近使用的条目位于链表尾部，便于淘汰旧条目。这种策略适用于大多数场景，因为它能够很好地保留热点数据。

```java
// LruCache 最近最少使用缓存
public class LruCache implements Cache {
  private final Cache delegate;  // 被装饰的缓存
  private final Map<Object, Object> keyMap;  // 键映射（按访问顺序）
  private Object eldestKey;       // 最久未使用的键
  
  public LruCache(Cache delegate) {
    this.delegate = delegate;
    setSize(1024);  // 默认大小
  }
  
  public void setSize(final int size) {
    // 使用 LinkedHashMap 实现 LRU
    keyMap = new LinkedHashMap<Object, Object>(size, .75F, true) {
      private static final long serialVersionUID = 4267176411845948333L;
      
      @Override
      protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
        boolean tooBig = size() > size;
        if (tooBig) {
          eldestKey = eldest.getKey();  // 记录将被淘汰的键
        }
        return tooBig;
      }
    };
  }
  
  @Override
  public void putObject(Object key, Object value) {
    delegate.putObject(key, value);  // 委托存储
    cycleKeyList(key);                // 更新访问顺序
  }
  
  @Override
  public Object getObject(Object key) {
    keyMap.get(key);  // 访问该键，更新其在 LinkedHashMap 中的位置
    return delegate.getObject(key);
  }
  
  private void cycleKeyList(Object key) {
    keyMap.put(key, key);  // 记录访问
    if (eldestKey != null) {
      delegate.removeObject(eldestKey);  // 淘汰最久未使用的条目
      eldestKey = null;
    }
  }
}
```

### 5.2 FifoCache 先进先出缓存

FifoCache（First In First Out Cache）采用先进先出的淘汰策略，当缓存满时，最早进入缓存的条目会被优先移除。FifoCache 使用双向队列（LinkedList）记录缓存键的插入顺序，每次添加新条目时将其加入队列尾部，当队列大小超过限制时从头部移除最早的条目。这种策略实现简单，适用于对数据时效性要求不高的场景。

```java
// FifoCache 先进先出缓存
public class FifoCache implements Cache {
  private final Cache delegate;        // 被装饰的缓存
  private final Deque<Object> keyList; // 键队列（按插入顺序）
  private int size;                    // 最大容量
  
  public FifoCache(Cache delegate) {
    this.delegate = delegate;
    this.keyList = new LinkedList<Object>();
    this.size = 1024;  // 默认大小
  }
  
  public void setSize(int size) {
    this.size = size;
  }
  
  @Override
  public void putObject(Object key, Object value) {
    cycleKeyList(key);               // 检查是否需要淘汰
    delegate.putObject(key, value);  // 存储新条目
  }
  
  private void cycleKeyList(Object key) {
    keyList.addLast(key);  // 新键加入队列尾部
    if (keyList.size() > size) {
      Object oldestKey = keyList.removeFirst();  // 移除队首（最早的条目）
      delegate.removeObject(oldestKey);
    }
  }
}
```

### 5.3 BlockingCache 阻塞缓存

BlockingCache 是用于解决缓存击穿问题的装饰器，它通过锁机制确保同一时间只有一个线程能够从数据库加载特定缓存条目。当多个线程同时查询一个未缓存的数据时，第一个获取锁的线程会从数据库加载数据，其他线程则等待锁释放后直接从缓存获取。这种策略避免了缓存失效时大量并发请求同时访问数据库的问题。

```java
// BlockingCache 阻塞缓存
public class BlockingCache implements Cache {
  private long timeout;                         // 获取锁的超时时间
  private final Cache delegate;                 // 被装饰的缓存
  private final ConcurrentHashMap<Object, ReentrantLock> locks;  // 锁映射
  
  public BlockingCache(Cache delegate) {
    this.delegate = delegate;
    this.locks = new ConcurrentHashMap<Object, ReentrantLock>();
  }
  
  @Override
  public Object getObject(Object key) {
    acquireLock(key);  // 获取锁
    try {
      Object value = delegate.getObject(key);
      if (value != null) {
        releaseLock(key);  // 命中则释放锁
      }
      return value;
    } finally {
      // 如果未命中，锁会在 removeObject 时释放
    }
  }
  
  @Override
  public void putObject(Object key, Object value) {
    try {
      delegate.putObject(key, value);
    } finally {
      releaseLock(key);  // 写入完成后释放锁
    }
  }
  
  private void acquireLock(Object key) {
    Lock lock = getLockForKey(key);
    if (timeout > 0) {
      try {
        boolean acquired = lock.tryLock(timeout, TimeUnit.MILLISECONDS);
        if (!acquired) {
          throw new CacheException("获取缓存锁超时: " + key);
        }
      } catch (InterruptedException e) {
        throw new CacheException("获取缓存锁被中断", e);
      }
    } else {
      lock.lock();  // 阻塞等待
    }
  }
  
  private void releaseLock(Object key) {
    ReentrantLock lock = locks.get(key);
    if (lock.isHeldByCurrentThread()) {
      lock.unlock();
    }
  }
}
```

### 5.4 TransactionalCache 事务缓存

TransactionalCache 是专门为二级缓存设计的事务性缓存装饰器，它在事务提交前将所有缓存操作暂存在缓冲区中，确保事务的原子性。当事务提交时，缓冲区中的数据会被一次性刷新到实际缓存；如果事务回滚，缓冲区中的数据则被丢弃。这种设计保证了在事务执行过程中，即使发生异常也不会产生脏数据。

```java
// TransactionalCache 事务缓存
public class TransactionalCache implements Cache {
  private final Cache delegate;                   // 实际缓存
  private boolean clearOnCommit;                  // 提交时是否清空
  private final Map<Object, Object> entriesToAddOnCommit;  // 待提交数据
  private final Set<Object> entriesMissedInCache; // 缓存未命中集合
  
  @Override
  public Object getObject(Object key) {
    Object object = delegate.getObject(key);
    if (object == null) {
      entriesMissedInCache.add(key);  // 记录未命中
    }
    if (clearOnCommit) {
      return null;  // 即将清空，返回 null
    }
    return object;
  }
  
  @Override
  public void putObject(Object key, Object object) {
    entriesToAddOnCommit.put(key, object);  // 仅写入缓冲区
  }
  
  public void commit() {
    if (clearOnCommit) {
      delegate.clear();  // 清空实际缓存
    }
    flushPendingEntries();  // 刷新待提交数据
    reset();
  }
  
  public void rollback() {
    unlockMissedEntries();  // 解锁未命中的条目
    reset();
  }
  
  private void flushPendingEntries() {
    // 将缓冲区数据写入实际缓存
    for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
      delegate.putObject(entry.getKey(), entry.getValue());
    }
    // 处理缓存未命中的条目（写入 null）
    for (Object entry : entriesMissedInCache) {
      if (!entriesToAddOnCommit.containsKey(entry)) {
        delegate.putObject(entry, null);
      }
    }
  }
}
```

### 5.5 SerializedCache 序列化缓存

SerializedCache 提供了缓存值的序列化功能，使得缓存对象可以被序列化存储或传输。当 readWrite 设置为 true 时，MyBatis 会自动添加此装饰器。序列化缓存适用于需要跨进程共享缓存或将缓存持久化的场景。序列化过程会将对象转换为字节数组，存储时使用序列化后的字节数组，读取时再反序列化为对象。需要注意的是，被缓存的对象必须实现 Serializable 接口。

```java
// SerializedCache 序列化缓存
public class SerializedCache implements Cache {
  private final Cache delegate;
  
  @Override
  public void putObject(Object key, Object object) {
    if (object == null || object instanceof Serializable) {
      // 序列化后存储
      delegate.putObject(key, serialize((Serializable) object));
    } else {
      throw new CacheException("非序列化对象无法存储: " + object);
    }
  }
  
  @Override
  public Object getObject(Object key) {
    Object object = delegate.getObject(key);
    return object == null ? null : deserialize((byte[]) object);
  }
  
  // 序列化方法
  private byte[] serialize(Serializable value) {
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      ObjectOutputStream oos = new ObjectOutputStream(bos);
      oos.writeObject(value);
      oos.flush();
      oos.close();
      return bos.toByteArray();
    } catch (Exception e) {
      throw new CacheException("序列化失败", e);
    }
  }
  
  // 反序列化方法
  private Serializable deserialize(byte[] value) {
    try {
      ByteArrayInputStream bis = new ByteArrayInputStream(value);
      ObjectInputStream ois = new CustomObjectInputStream(bis);
      Serializable result = (Serializable) ois.readObject();
      ois.close();
      return result;
    } catch (Exception e) {
      throw new CacheException("反序列化失败", e);
    }
  }
}
```

### 5.6 其他装饰器

除了上述核心装饰器外，MyBatis 还提供了其他几个功能各异的装饰器。ScheduledCache 提供定时刷新功能，可以设置固定时间间隔自动清空缓存，适用于数据时效性要求较高的场景。LoggingCache 为缓存操作添加日志记录功能，可以追踪缓存的命中率和操作情况。SynchronizedCache 为缓存操作添加同步锁，确保线程安全。SoftCache 和 WeakCache 分别使用软引用和弱引用存储缓存条目，允许垃圾回收器在内存不足时自动回收缓存对象。

## 六、缓存工作流程

### 6.1 查询流程中的缓存查找

MyBatis 在执行查询操作时会按照特定的顺序查找缓存，这个过程涉及一级缓存和二级缓存的协作。查询流程首先检查一级缓存，如果命中则直接返回结果；如果未命中，则继续检查二级缓存。二级缓存的查找通过 TransactionalCacheManager 进行，它管理着当前事务相关的所有事务性缓存。最终，如果两级缓存都未命中，则执行数据库查询，将结果存入缓存后返回。

```java
// 查询流程中的缓存查找顺序
public class CachingExecutor implements Executor {
  private final Executor delegate;
  private final TransactionalCacheManager tcm = new TransactionalCacheManager();
  
  @Override
  public <E> List<E> query(MappedStatement ms, Object parameter,
                           RowBounds rowBounds, ResultHandler handler) {
    // 1. 获取缓存键
    CacheKey key = createCacheKey(ms, parameter, rowBounds);
    Cache cache = ms.getCache();
    
    // 2. 查找二级缓存
    if (cache != null) {
      // 从 TransactionalCache 获取
      Object cacheValue = tcm.getObject(cache, key);
      if (cacheValue != null) {
        return (List<E>) cacheValue;  // 二级缓存命中
      }
    }
    
    // 3. 执行数据库查询（委托给基础执行器）
    List<E> result = delegate.query(ms, parameter, rowBounds, handler);
    
    // 4. 存入二级缓存
    if (cache != null) {
      tcm.putObject(cache, key, result);
    }
    
    return result;
  }
}
```

### 6.2 写入流程中的缓存更新

写入操作（insert、update、delete）会触发缓存的更新或失效。在一级缓存中，写入操作会导致当前 SqlSession 的缓存被清空。在二级缓存中，写入操作会影响相同命名空间下的所有缓存条目，因为被修改的数据可能已经存在于缓存中。MyBatis 通过在写入操作后自动刷新相关缓存来保证数据一致性。

```java
// 写入流程中的缓存更新
public class CachingExecutor implements Executor {
  
  @Override
  public int update(MappedStatement ms, Object parameter) {
    // 1. 执行实际更新操作
    int result = delegate.update(ms, parameter);
    
    // 2. 使相关缓存失效
    flushCacheIfRequired(ms);
    
    return result;
  }
  
  private void flushCacheIfRequired(MappedStatement ms) {
    // 检查是否需要刷新缓存
    if (ms.isFlushCacheRequired()) {
      // 获取缓存
      Cache cache = ms.getCache();
      if (cache != null) {
        // 清空二级缓存
        tcm.clear(cache);
      }
    }
  }
}
```

### 6.3 完整执行流程图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       MyBatis 查询执行完整流程                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  用户调用 selectOne/statementId, parameter                               │
│                              ↓                                          │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                      CachingExecutor                             │   │
│  │                    （缓存执行器包装）                              │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                              ↓                                          │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                   生成 CacheKey                                   │   │
│  │  ├─ MappedStatement ID                                           │   │
│  │  ├─ 查询参数                                                     │   │
│  │  ├─ RowBounds（分页信息）                                         │   │
│  │  └─ Configuration                                                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                              ↓                                          │
│                    ┌─────────────┐                                      │
│                    │  二级缓存？  │                                      │
│                    └─────────────┘                                      │
│                         ↓ │                                             │
│                        是 ↓ 否                                          │
│                         ↓   ↓                                           │
│              ┌─────────────────────┐                                    │
│              │  从 TransactionalCache │                                   │
│              │        获取数据       │                                   │
│              └─────────────────────┘                                    │
│                         ↓                                               │
│              ┌─────────────────────┐                                    │
│              │  delegate.query()   │                                    │
│              │  （基础执行器查询）   │                                    │
│              └─────────────────────┘                                    │
│                         ↓                                               │
│                    ┌─────────────┐                                      │
│                    │  一级缓存？  │                                      │
│                    └─────────────┘                                      │
│                         ↓ │                                             │
│                        是 ↓ 否                                          │
│                         ↓   ↓                                           │
│              ┌─────────────────────┐                                    │
│              │   LocalCache 命中    │                                    │
│              │   （返回结果）        │                                    │
│              └─────────────────────┘                                    │
│                         ↓                                               │
│              ┌─────────────────────┐                                    │
│              │  执行器查询数据库     │                                    │
│              │  （BaseExecutor）    │                                    │
│              └─────────────────────┘                                    │
│                         ↓                                               │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                      结果处理                                      │   │
│  │  ├─ 存入一级缓存                                                   │   │
│  │  ├─ 根据配置决定是否存入二级缓存                                    │   │
│  │  └─ 返回查询结果                                                   │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 6.4 事务场景下的缓存行为

在事务场景下，缓存的行为会有所不同。一级缓存会随着事务的提交或回滚而被清空，确保事务内的数据一致性。二级缓存通过 TransactionalCache 实现事务性缓冲，在事务提交前缓存数据被暂存于缓冲区中，只有事务成功提交后才会刷新到实际的二级缓存。如果事务回滚，缓冲区中的数据会被丢弃，不会污染二级缓存。

```java
// 事务场景下的缓存行为
SqlSession sqlSession = sqlSessionFactory.openSession();
try {
  // 事务开始
  
  // 查询操作
  User user1 = sqlSession.selectOne("selectById", 1);  // 存入一级缓存
  
  // 更新操作
  sqlSession.update("updateUser", user);  // 清空一级缓存
  
  // 提交事务
  sqlSession.commit();  // 一级缓存被清空
  
  // 另一个 SqlSession 的查询
  SqlSession sqlSession2 = sqlSessionFactory.openSession();
  User user2 = sqlSession2.selectOne("selectById", 1);  // 可能命中二级缓存
  sqlSession2.commit();
  
} catch (Exception e) {
  sqlSession.rollback();  // 回滚事务，一级缓存被清空
} finally {
  sqlSession.close();
}
```

## 七、配置与使用

### 7.1 全局缓存配置

MyBatis 的全局缓存配置通过 settings 元素进行设置。一级缓存的作用域可以通过 localCacheScope 配置，二级缓存的全局开关可以通过 cacheEnabled 配置。这些配置会影响到整个应用的所有 Mapper，是最基础的缓存配置。

```xml
<!-- mybatis-config.xml 全局配置 -->
<configuration>
  <settings>
    <!-- 是否启用二级缓存（默认 true） -->
    <setting name="cacheEnabled" value="true"/>
    
    <!-- 一级缓存作用域：SESSION 或 STATEMENT（默认 SESSION） -->
    <setting name="localCacheScope" value="SESSION"/>
    
    <!-- JDBC 类型对于 NULL 值的处理（默认 OTHER） -->
    <setting name="jdbcTypeForNull" value="NULL"/>
  </settings>
</configuration>
```

### 7.2 Mapper 级别缓存配置

在 Mapper 级别，可以为每个命名空间单独配置二级缓存。cache 元素用于启用和配置命名空间的缓存属性，包括缓存实现类、淘汰策略、大小、刷新间隔等。cache-ref 元素用于引用其他命名空间的缓存配置，实现缓存共享。

```xml
<!-- Mapper XML 缓存配置示例 -->
<mapper namespace="com.mapper.UserMapper">
  
  <!-- 启用并配置二级缓存 -->
  <cache
    eviction="LRU"                    <!-- 淘汰策略：LRU、FIFO、SOFT、WEAK -->
    flushInterval="60000"             <!-- 自动刷新间隔：60秒 -->
    size="512"                        <!-- 缓存最大条目数 -->
    readWrite="true"                  <!-- 是否可读写（序列化） -->
    blocking="false"                  <!-- 是否启用阻塞 -->
    type="org.apache.ibatis.cache.impl.PerpetualCache"/>  <!-- 缓存实现类 -->
  
  <!-- 使用自定义缓存实现 -->
  <cache type="com.myapp.MyCustomCache">
    <property name="cacheFile" value="/tmp/mybatis-cache"/>
  </cache>
  
  <!-- 引用其他命名空间的缓存 -->
  <cache-ref namespace="com.mapper.BaseMapper"/>
  
  <!-- 查询配置 -->
  <select id="selectById" parameterType="int"
          useCache="true"            <!-- 是否使用缓存 -->
          flushCache="false"         <!-- 执行后是否刷新缓存 -->
          timeout="10000">
    SELECT * FROM user WHERE id = #{id}
  </select>
  
  <!-- 写入配置 -->
  <insert id="insert" flushCache="true">
    INSERT INTO user(name) VALUES(#{name})
  </insert>
  
</mapper>
```

### 7.3 注解方式缓存配置

对于使用注解配置 Mapper 的场景，可以通过 @CacheNamespace 注解启用和配置二级缓存。该注解支持配置淘汰策略类、大小、刷新间隔等属性。如果需要共享缓存，可以使用 @CacheNamespaceRef 注解引用其他命名空间。

```java
// 注解方式缓存配置
@CacheNamespace(
  implementation = PerpetualCache.class,      // 缓存实现类
  decorators = {                               // 装饰器列表
    LruCache.class,        // LRU 淘汰策略
    SerializedCache.class, // 序列化支持
    ScheduledCache.class,  // 定时刷新
    LoggingCache.class,    // 日志记录
    SynchronizedCache.class  // 同步支持
  },
  eviction = LruCache.class,     // 默认淘汰策略
  flushInterval = 60000,         // 刷新间隔（毫秒）
  size = 512,                    // 缓存大小
  readWrite = true,              // 是否可读写
  blocking = false               // 是否阻塞
)
public interface UserMapper {
  
  @Select("SELECT * FROM user WHERE id = #{id}")
  @Options(useCache = true, flushCache = Options.FlushCachePolicy.FALSE)
  User selectById(Integer id);
  
  @Insert("INSERT INTO user(name) VALUES(#{name})")
  @Options(flushCache = Options.FlushCachePolicy.TRUE)
  int insert(User user);
}
```

### 7.4 自定义缓存实现

MyBatis 允许开发者实现自定义的缓存实现来满足特殊需求。自定义缓存类必须实现 Cache 接口，并提供以 String（缓存 ID）为参数的构造函数。自定义缓存可以实现各种存储后端，如 Redis、EhCache、Memcached 等。

```java
// 自定义缓存实现示例
public class MyCustomCache implements Cache {
  private final String id;
  private final Map<Object, Object> storage = new ConcurrentHashMap<>();
  
  public MyCustomCache(String id) {
    this.id = id;
  }
  
  @Override
  public String getId() {
    return id;
  }
  
  @Override
  public void putObject(Object key, Object value) {
    storage.put(key, value);
  }
  
  @Override
  public Object getObject(Object key) {
    return storage.get(key);
  }
  
  @Override
  public Object removeObject(Object key) {
    return storage.remove(key);
  }
  
  @Override
  public void clear() {
    storage.clear();
  }
  
  @Override
  public int getSize() {
    return storage.size();
  }
  
  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }
}
```

## 八、设计模式分析

### 8.1 装饰器模式的应用

MyBatis 缓存体系大量使用了装饰器模式，这是其缓存设计的核心模式。装饰器模式允许在运行时动态地为对象添加额外的功能，而无需修改其源代码。在缓存体系中，PerpetualCache 作为基础组件，各种装饰器（LruCache、FifoCache、BlockingCache 等）层层包装在其外部，每个装饰器只负责单一的功能增强。这种设计使得缓存系统具有良好的扩展性，新增功能只需添加新的装饰器即可。

```java
// 装饰器模式应用示例
// 构建带有多层装饰的缓存
Cache cache = new PerpetualCache("userCache");           // 基础缓存
cache = new LruCache(cache);                             // 添加 LRU 淘汰
cache = new ScheduledCache(cache);                       // 添加定时刷新
cache = new SerializedCache(cache);                      // 添加序列化
cache = new LoggingCache(cache);                         // 添加日志
cache = new SynchronizedCache(cache);                    // 添加同步
cache = new BlockingCache(cache);                        // 添加阻塞

// 实际使用
cache.putObject(key, value);  // 数据会依次经过各装饰器处理
```

### 8.2 策略模式的应用

缓存淘汰策略体现了策略模式的应用。LruCache、FifoCache、SoftCache、WeakCache 等不同的淘汰策略可以相互替换，客户端可以根据需求选择合适的策略。策略模式将这些算法封装成独立的类，使得它们可以独立于客户端而变化。

```java
// 策略模式应用示例
// 配置不同的淘汰策略
@CacheNamespace(eviction = LruCache.class)     // LRU 策略
public interface UserMapper { ... }

@CacheNamespace(eviction = FifoCache.class)    // FIFO 策略
public interface OrderMapper { ... }
```

### 8.3 建造者模式的应用

CacheBuilder 使用了建造者模式来构建复杂的缓存对象。建造者模式将对象的构建过程与其表示分离，使得同样的构建过程可以创建不同的表示。CacheBuilder 通过链式调用设置各种属性，最后通过 build() 方法完成缓存的构建。

```java
// 建造者模式应用示例
Cache cache = new CacheBuilder("com.mapper.UserMapper")
  .implementation(PerpetualCache.class)
  .addDecorator(LruCache.class)
  .size(512)
  .clearInterval(60000L)
  .readWrite(true)
  .blocking(false)
  .build();
```

## 九、最佳实践

### 9.1 缓存配置建议

在配置 MyBatis 缓存时，需要根据实际业务场景选择合适的配置策略。对于读多写少的场景，可以启用二级缓存并设置较长的刷新间隔；对于读写均衡的场景，需要权衡缓存命中率和数据一致性的关系；对于写多读少的场景，二级缓存的价值有限，可以考虑禁用。淘汰策略的选择应根据数据访问模式决定：热点数据使用 LRU，周期性数据使用 FIFO，内存敏感场景使用 SOFT 或 WEAK。

```xml
<!-- 读多写少场景配置 -->
<cache
  eviction="LRU"
  flushInterval="300000"    <!-- 5分钟刷新 -->
  size="1024"
  readWrite="true"/>

<!-- 读写均衡场景配置 -->
<cache
  eviction="FIFO"
  flushInterval="60000"     <!-- 1分钟刷新 -->
  size="512"
  readWrite="true"/>

<!-- 内存敏感场景配置 -->
<cache
  eviction="SOFT"
  size="256"/>
```

### 9.2 常见问题与解决方案

在使用 MyBatis 缓存时，可能会遇到缓存与数据库数据不一致、缓存穿透、缓存击穿等问题。对于数据不一致问题，需要合理设置 flushInterval 并在数据变更后及时刷新缓存。对于缓存穿透问题，可以在查询结果为 null 时也缓存空值（需要使用 BlockingCache 配合）。对于缓存击穿问题，应启用 BlockingCache 装饰器。序列化异常通常是因为缓存了不可序列化的对象，需要确保缓存对象的类实现了 Serializable 接口。

```java
// 解决缓存穿透问题：缓存空值
@Transactional
public User selectById(Integer id) {
  User user = userMapper.selectById(id);
  // 将 null 也缓存起来，防止缓存穿透
  if (user == null) {
    cache.putObject("user:" + id, NullCacheKey.INSTANCE);
  } else {
    cache.putObject("user:" + id, user);
  }
  return user;
}
```

### 9.3 性能优化建议

为了充分发挥缓存的性能优势，建议遵循以下优化原则：合理设置缓存大小，避免过大导致内存压力或过小导致频繁淘汰；对于频繁访问的数据，可以考虑使用 readOnly=true 跳过序列化；对于不需要序列化的场景，可以禁用 SerializedCache 以减少 CPU 开销；使用分页查询时注意 RowBounds 不同会生成不同的缓存键；避免在缓存中存储过大的对象，可以存储 ID 而非完整对象。

```java
// 性能优化示例：禁用不必要的序列化
@CacheNamespace(
  implementation = PerpetualCache.class,
  decorators = {
    LruCache.class,
    LoggingCache.class,
    SynchronizedCache.class
    // 不添加 SerializedCache
  }
)
public interface UserMapper { ... }
```

## 十、总结

MyBatis 的缓存体系是一个设计精良、功能完善的缓存解决方案。通过一级缓存和二级缓存的两级架构，MyBatis 实现了从 SqlSession 级别到命名空间级别的全方位缓存覆盖。装饰器模式的应用使得缓存系统具有良好的扩展性，可以通过灵活组合不同的装饰器满足各种业务需求。事务性缓存设计保证了在事务场景下缓存数据的一致性。CacheKey 的设计确保了缓存键的唯一性和分布均匀性。

在生产环境中，合理配置和使用 MyBatis 缓存能够显著提升系统性能。建议根据业务特点选择合适的淘汰策略、刷新间隔和缓存大小，并注意处理缓存与数据库数据的一致性问题。对于高并发场景，应启用阻塞缓存装饰器以避免缓存击穿。同时，应定期监控缓存的命中率和内存使用情况，及时调整配置以优化性能。
