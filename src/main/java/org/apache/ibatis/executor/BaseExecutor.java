/**
 * Copyright 2009-2018 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.executor;

import static org.apache.ibatis.executor.ExecutionPlaceholder.EXECUTION_PLACEHOLDER;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementUtil;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.jdbc.ConnectionLogger;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 */
public abstract class BaseExecutor implements Executor {

    private static final Log log = LogFactory.getLog(BaseExecutor.class);

    protected Transaction transaction;
    protected Executor wrapper;

    protected ConcurrentLinkedQueue<DeferredLoad> deferredLoads;
    protected PerpetualCache localCache;
    protected PerpetualCache localOutputParameterCache;
    protected Configuration configuration;

    protected int queryStack;
    private boolean closed;

    protected BaseExecutor(Configuration configuration, Transaction transaction) {
        this.transaction = transaction;
        this.deferredLoads = new ConcurrentLinkedQueue<DeferredLoad>();
        this.localCache = new PerpetualCache("LocalCache");
        this.localOutputParameterCache = new PerpetualCache("LocalOutputParameterCache");
        this.closed = false;
        this.configuration = configuration;
        this.wrapper = this;
    }

    @Override
    public Transaction getTransaction() {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        return transaction;
    }

    @Override
    public void close(boolean forceRollback) {
        try {
            try {
                rollback(forceRollback);
            } finally {
                if (transaction != null) {
                    transaction.close();
                }
            }
        } catch (SQLException e) {
            // Ignore.  There's nothing that can be done at this point.
            log.warn("Unexpected exception on closing transaction.  Cause: " + e);
        } finally {
            transaction = null;
            deferredLoads = null;
            localCache = null;
            localOutputParameterCache = null;
            closed = true;
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public int update(MappedStatement ms, Object parameter) throws SQLException {
        ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId());
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        clearLocalCache();
        return doUpdate(ms, parameter);
    }

    @Override
    public List<BatchResult> flushStatements() throws SQLException {
        return flushStatements(false);
    }

    public List<BatchResult> flushStatements(boolean isRollBack) throws SQLException {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        return doFlushStatements(isRollBack);
    }

    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
        //
        BoundSql boundSql = ms.getBoundSql(parameter);

        CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);

        return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
    }

    /**
     * 执行查询操作
     * 
     * 此方法是 MyBatis 查询执行的核心方法，负责处理查询的整个生命周期，
     * 包括缓存检查、查询执行、结果处理和延迟加载等。
     * 
     * 查询流程：
     * 1. 设置错误上下文，便于异常追踪
     * 2. 检查执行器状态
     * 3. 根据配置决定是否清空本地缓存
     * 4. 检查一级缓存，如果命中则直接返回
     * 5. 如果缓存未命中，则从数据库查询
     * 6. 处理延迟加载
     * 7. 根据本地缓存作用域决定是否清空缓存
     * 
     * 一级缓存（本地缓存）：
     * - 默认作用域为 SESSION，在 SqlSession 生命周期内有效
     * - 可配置为 STATEMENT，仅在当前语句内有效
     * - 在查询前检查，在查询后存储
     * 
     * 延迟加载处理：
     * - 延迟加载的属性会在查询完成后处理
     * - 使用 DeferredLoad 对象管理延迟加载
     * - 确保所有延迟加载的属性都能正确加载
     * 
     * @param <E> 返回结果列表的元素类型
     * @param ms 映射语句对象，包含 SQL 语句和配置信息
     * @param parameter 参数对象，包含 SQL 参数的值
     * @param rowBounds 行边界对象，包含分页信息
     * @param resultHandler 结果处理器，用于自定义结果处理
     * @param key 缓存键，用于标识查询结果
     * @param boundSql 绑定的 SQL 对象，包含 SQL 语句和参数映射
     * @return 查询结果列表
     * @throws SQLException 如果查询过程中发生数据库错误
     */
    @SuppressWarnings("unchecked")
    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
        // 设置错误上下文，便于异常追踪和调试
        ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
        
        // 检查执行器是否已关闭，如果关闭则抛出异常
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        
        // 如果查询栈为0（表示这是最外层查询）且需要清空缓存，则清空本地缓存
        // 某些语句（如插入、更新、删除）可能要求在查询前清空缓存
        if (queryStack == 0 && ms.isFlushCacheRequired()) {
            clearLocalCache();
        }
        
        List<E> list;
        try {
            // 增加查询栈计数，用于处理嵌套查询
            queryStack++;
            
            // 如果没有自定义结果处理器，尝试从本地缓存获取结果
            // 如果有自定义结果处理器，则不使用缓存，直接查询数据库
            list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
            
            if (list != null) {
                // 如果缓存命中，处理存储过程的输出参数
                handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
            } else {
                // 如果缓存未命中，从数据库查询
                list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
            }
        } finally {
            // 减少查询栈计数
            queryStack--;
        }
        
        // 如果查询栈为0（表示这是最外层查询），处理延迟加载
        if (queryStack == 0) {
            // 执行所有延迟加载
            for (DeferredLoad deferredLoad : deferredLoads) {
                deferredLoad.load();
            }
            // 清空延迟加载列表，解决 issue #601
            deferredLoads.clear();
            
            // 如果本地缓存作用域为 STATEMENT，则清空本地缓存
            // 这样可以确保缓存仅在当前语句内有效，解决 issue #482
            if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
                clearLocalCache();
            }
        }
        
        return list;
    }

    @Override
    public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
        BoundSql boundSql = ms.getBoundSql(parameter);
        return doQueryCursor(ms, parameter, rowBounds, boundSql);
    }

    @Override
    public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        DeferredLoad deferredLoad = new DeferredLoad(resultObject, property, key, localCache, configuration, targetType);
        if (deferredLoad.canLoad()) {
            deferredLoad.load();
        } else {
            deferredLoads.add(new DeferredLoad(resultObject, property, key, localCache, configuration, targetType));
        }
    }

    /**
     * 创建查询的缓存键
     * 
     * 此方法根据查询的各种参数创建一个唯一的缓存键，用于标识和检索查询结果。
     * 缓存键的生成需要考虑所有可能影响查询结果的参数，确保缓存键的唯一性和准确性。
     * 
     * 缓存键组成要素：
     * 1. MappedStatement ID - 标识具体的 SQL 语句
     * 2. RowBounds 偏移量和限制 - 分页参数
     * 3. SQL 语句 - 实际执行的 SQL
     * 4. 参数值 - SQL 参数的具体值
     * 5. 环境ID - 数据源环境标识
     * 
     * 参数值获取逻辑：
     * 1. 首先检查 BoundSql 中的额外参数
     * 2. 如果参数对象为 null，则值为 null
     * 3. 如果参数对象有类型处理器，则直接使用参数对象
     * 4. 否则通过反射获取参数对象中对应属性的值
     * 
     * @param ms 映射语句对象，包含 SQL 语句和配置信息
     * @param parameterObject 参数对象，包含 SQL 参数的值
     * @param rowBounds 行边界对象，包含分页信息
     * @param boundSql 绑定的 SQL 对象，包含 SQL 语句和参数映射
     * @return 创建的缓存键对象
     * @throws ExecutorException 如果执行器已关闭
     */
    @Override
    public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
        // 检查执行器是否已关闭，如果关闭则抛出异常
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        
        // 创建新的缓存键对象
        CacheKey cacheKey = new CacheKey();
        
        // 添加映射语句的 ID，用于标识具体的 SQL 语句
        cacheKey.update(ms.getId());
        
        // 添加分页参数：偏移量和限制
        cacheKey.update(rowBounds.getOffset());
        cacheKey.update(rowBounds.getLimit());
        
        // 添加 SQL 语句，确保 SQL 的变化会影响缓存键
        cacheKey.update(boundSql.getSql());
        
        // 获取参数映射列表，用于处理参数值
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        // 获取类型处理器注册表，用于判断参数类型
        TypeHandlerRegistry typeHandlerRegistry = ms.getConfiguration().getTypeHandlerRegistry();
        
        // 模仿 DefaultParameterHandler 的逻辑，处理每个参数
        for (ParameterMapping parameterMapping : parameterMappings) {
            // 跳过 OUT 模式的参数，因为它们不影响查询结果
            if (parameterMapping.getMode() != ParameterMode.OUT) {
                Object value;
                String propertyName = parameterMapping.getProperty();
                
                // 优先从 BoundSql 的额外参数中获取值
                if (boundSql.hasAdditionalParameter(propertyName)) {
                    value = boundSql.getAdditionalParameter(propertyName);
                } 
                // 如果参数对象为 null，则值为 null
                else if (parameterObject == null) {
                    value = null;
                } 
                // 如果参数对象有类型处理器，则直接使用参数对象
                else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
                    value = parameterObject;
                } 
                // 否则通过反射获取参数对象中对应属性的值
                else {
                    MetaObject metaObject = configuration.newMetaObject(parameterObject);
                    value = metaObject.getValue(propertyName);
                }
                
                // 将参数值添加到缓存键中
                cacheKey.update(value);
            }
        }
        
        // 添加环境 ID，确保不同数据源的查询不会相互影响
        // 解决 issue #176
        if (configuration.getEnvironment() != null) {
            cacheKey.update(configuration.getEnvironment().getId());
        }
        
        return cacheKey;
    }

    @Override
    public boolean isCached(MappedStatement ms, CacheKey key) {
        return localCache.getObject(key) != null;
    }

    @Override
    public void commit(boolean required) throws SQLException {
        if (closed) {
            throw new ExecutorException("Cannot commit, transaction is already closed");
        }
        clearLocalCache();
        flushStatements();
        if (required) {
            transaction.commit();
        }
    }

    @Override
    public void rollback(boolean required) throws SQLException {
        if (!closed) {
            try {
                clearLocalCache();
                flushStatements(true);
            } finally {
                if (required) {
                    transaction.rollback();
                }
            }
        }
    }

    @Override
    public void clearLocalCache() {
        if (!closed) {
            localCache.clear();
            localOutputParameterCache.clear();
        }
    }

    protected abstract int doUpdate(MappedStatement ms, Object parameter)
            throws SQLException;

    protected abstract List<BatchResult> doFlushStatements(boolean isRollback)
            throws SQLException;

    protected abstract <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
            throws SQLException;

    protected abstract <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
            throws SQLException;

    protected void closeStatement(Statement statement) {
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException e) {
                // ignore
            }
        }
    }

    /**
     * Apply a transaction timeout.
     *
     * @param statement a current statement
     * @throws SQLException if a database access error occurs, this method is called on a closed <code>Statement</code>
     * @see StatementUtil#applyTransactionTimeout(Statement, Integer, Integer)
     * @since 3.4.0
     */
    protected void applyTransactionTimeout(Statement statement) throws SQLException {
        StatementUtil.applyTransactionTimeout(statement, statement.getQueryTimeout(), transaction.getTimeout());
    }

    private void handleLocallyCachedOutputParameters(MappedStatement ms, CacheKey key, Object parameter, BoundSql boundSql) {
        if (ms.getStatementType() == StatementType.CALLABLE) {
            final Object cachedParameter = localOutputParameterCache.getObject(key);
            if (cachedParameter != null && parameter != null) {
                final MetaObject metaCachedParameter = configuration.newMetaObject(cachedParameter);
                final MetaObject metaParameter = configuration.newMetaObject(parameter);
                for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
                    if (parameterMapping.getMode() != ParameterMode.IN) {
                        final String parameterName = parameterMapping.getProperty();
                        final Object cachedValue = metaCachedParameter.getValue(parameterName);
                        metaParameter.setValue(parameterName, cachedValue);
                    }
                }
            }
        }
    }

    /**
     * 从数据库执行查询操作，当一级缓存中不存在所需数据时调用此方法
     * 
     * 此方法是MyBatis查询执行的核心实现之一，负责实际访问数据库并处理结果缓存。
     * 它通过使用占位符机制防止递归查询，并在查询完成后将结果存入一级缓存。
     * 
     * 执行流程：
     * 1. 在本地缓存中放置占位符，防止相同查询的递归调用
     * 2. 调用抽象方法doQuery执行实际数据库查询
     * 3. 清除占位符
     * 4. 将查询结果存入本地缓存
     * 5. 对于存储过程，将输出参数缓存
     * 
     * @param <E> 返回列表的元素类型
     * @param ms 映射语句对象，包含SQL语句和配置信息
     * @param parameter SQL参数对象，可能是基本类型、Map或自定义POJO
     * @param rowBounds 行边界限制，用于分页查询
     * @param resultHandler 结果处理器，用于处理结果集
     * @param key 缓存键，用于标识和存储查询结果
     * @param boundSql 绑定的SQL对象，包含SQL语句和参数映射信息
     * @return 查询结果列表
     * @throws SQLException 如果数据库访问过程中发生错误
     */
    private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
        List<E> list;
        // 在本地缓存中放置占位符，防止相同查询的递归调用
        // 这是一种防止循环引用的机制，当相同的查询正在执行时，
        // 其他线程或递归调用可以通过检查占位符避免重复执行
        localCache.putObject(key, EXECUTION_PLACEHOLDER);
        try {
            // 调用抽象方法执行实际查询，具体实现由子类提供
            // 如SimpleExecutor、ReuseExecutor或BatchExecutor
            list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
        } finally {
            // 无论查询成功与否，都清除占位符
            // 确保即使发生异常，占位符也能被正确清理
            localCache.removeObject(key);
        }
        // 将查询结果存入本地缓存，供后续相同查询使用
        // 这是MyBatis一级缓存的核心机制
        localCache.putObject(key, list);
        // 对于存储过程调用，需要缓存输出参数
        // 因为存储过程可能有OUT或INOUT参数，这些参数需要被缓存以便后续使用
        if (ms.getStatementType() == StatementType.CALLABLE) {
            localOutputParameterCache.putObject(key, parameter);
        }
        return list;
    }

    protected Connection getConnection(Log statementLog) throws SQLException {
        Connection connection = transaction.getConnection();
        if (statementLog.isDebugEnabled()) {
            return ConnectionLogger.newInstance(connection, statementLog, queryStack);
        } else {
            return connection;
        }
    }

    @Override
    public void setExecutorWrapper(Executor wrapper) {
        this.wrapper = wrapper;
    }

    private static class DeferredLoad {

        private final MetaObject resultObject;
        private final String property;
        private final Class<?> targetType;
        private final CacheKey key;
        private final PerpetualCache localCache;
        private final ObjectFactory objectFactory;
        private final ResultExtractor resultExtractor;

        // issue #781
        public DeferredLoad(MetaObject resultObject,
                            String property,
                            CacheKey key,
                            PerpetualCache localCache,
                            Configuration configuration,
                            Class<?> targetType) {
            this.resultObject = resultObject;
            this.property = property;
            this.key = key;
            this.localCache = localCache;
            this.objectFactory = configuration.getObjectFactory();
            this.resultExtractor = new ResultExtractor(configuration, objectFactory);
            this.targetType = targetType;
        }

        public boolean canLoad() {
            return localCache.getObject(key) != null && localCache.getObject(key) != EXECUTION_PLACEHOLDER;
        }

        public void load() {
            @SuppressWarnings("unchecked")
            // we suppose we get back a List
            List<Object> list = (List<Object>) localCache.getObject(key);
            Object value = resultExtractor.extractObjectFromList(list, targetType);
            resultObject.setValue(property, value);
        }

    }

}