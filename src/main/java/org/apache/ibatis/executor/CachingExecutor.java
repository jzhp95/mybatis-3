/**
 * Copyright 2009-2017 the original author or authors.
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

import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.TransactionalCacheManager;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class CachingExecutor implements Executor {

    private final Executor delegate;
    private final TransactionalCacheManager tcm = new TransactionalCacheManager();

    public CachingExecutor(Executor delegate) {
        this.delegate = delegate;
        delegate.setExecutorWrapper(this);
    }

    @Override
    public Transaction getTransaction() {
        return delegate.getTransaction();
    }

    @Override
    public void close(boolean forceRollback) {
        try {
            //issues #499, #524 and #573
            if (forceRollback) {
                tcm.rollback();
            } else {
                tcm.commit();
            }
        } finally {
            delegate.close(forceRollback);
        }
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public int update(MappedStatement ms, Object parameterObject) throws SQLException {
        flushCacheIfRequired(ms);
        return delegate.update(ms, parameterObject);
    }

    @Override
    public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
        BoundSql boundSql = ms.getBoundSql(parameterObject);
        CacheKey key = createCacheKey(ms, parameterObject, rowBounds, boundSql);
        return query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
    }

    @Override
    public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
        flushCacheIfRequired(ms);
        return delegate.queryCursor(ms, parameter, rowBounds);
    }

    /**
     * 执行查询操作，支持二级缓存机制
     * 
     * 这是 MyBatis 二级缓存的核心实现方法，负责：
     * 1. 检查并处理缓存刷新需求
     * 2. 从二级缓存中获取查询结果
     * 3. 如果缓存未命中，则委托底层执行器执行查询并缓存结果
     * 4. 处理存储过程 OUT 参数的限制
     * 
     * @param <E> 查询结果的类型
     * @param ms 映射语句，包含 SQL 语句和缓存配置信息
     * @param parameterObject 查询参数对象
     * @param rowBounds 分页参数
     * @param resultHandler 结果处理器，用于处理查询结果
     * @param key 缓存键，用于唯一标识查询请求
     * @param boundSql 绑定的 SQL 语句
     * @return 查询结果列表
     * @throws SQLException 如果查询过程中发生 SQL 异常
     * 
     * 执行流程：
     * 1. 检查映射语句是否配置了缓存
     * 2. 如果需要刷新缓存，则先清空相关缓存
     * 3. 检查是否启用缓存且没有结果处理器（结果处理器会绕过缓存）
     * 4. 确保存储过程没有 OUT 参数（不支持缓存）
     * 5. 从事务缓存管理器中获取缓存结果
     * 6. 如果缓存未命中，委托底层执行器执行查询并缓存结果
     * 7. 返回查询结果
     * 
     * 缓存策略：
     * - 只有在 ms.isUseCache() 为 true 且 resultHandler 为 null 时才使用缓存
     * - 存储过程包含 OUT 参数时不支持缓存
     * - 缓存结果在事务提交时才会真正写入二级缓存
     * 
     * 设计说明：
     * - 使用装饰器模式，CachingExecutor 包装底层执行器实现缓存功能
     * - 通过 TransactionalCacheManager 管理事务性缓存，确保事务一致性
     * - 缓存键基于 SQL 语句、参数和分页信息生成，确保唯一性
     */
    @Override
    public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql)
            throws SQLException {
        // 获取映射语句配置的缓存对象
        Cache cache = ms.getCache();
        if (cache != null) {
            // 如果需要刷新缓存（如执行了更新操作），则先清空缓存
            flushCacheIfRequired(ms);
            // 检查是否启用缓存且没有结果处理器（结果处理器会绕过缓存机制）
            if (ms.isUseCache() && resultHandler == null) {
                // 确保存储过程没有 OUT 参数（不支持带 OUT 参数的存储过程缓存）
                ensureNoOutParams(ms, boundSql);
                // 从事务缓存管理器中获取缓存结果
                @SuppressWarnings("unchecked")
                List<E> list = (List<E>) tcm.getObject(cache, key);
                // 如果缓存未命中
                if (list == null) {
                    // 委托底层执行器执行实际查询
                    list = delegate.<E>query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
                    // 将查询结果放入缓存（事务提交时才会真正写入二级缓存）
                    tcm.putObject(cache, key, list); // issue #578 and #116
                }
                return list;
            }
        }
        // 如果没有配置缓存或不符合缓存条件，直接委托底层执行器执行查询
        return delegate.<E>query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
    }

    @Override
    public List<BatchResult> flushStatements() throws SQLException {
        return delegate.flushStatements();
    }

    @Override
    public void commit(boolean required) throws SQLException {
        delegate.commit(required);
        tcm.commit();
    }

    @Override
    public void rollback(boolean required) throws SQLException {
        try {
            delegate.rollback(required);
        } finally {
            if (required) {
                tcm.rollback();
            }
        }
    }

    private void ensureNoOutParams(MappedStatement ms, BoundSql boundSql) {
        if (ms.getStatementType() == StatementType.CALLABLE) {
            for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
                if (parameterMapping.getMode() != ParameterMode.IN) {
                    throw new ExecutorException("Caching stored procedures with OUT params is not supported.  Please configure useCache=false in " + ms.getId() + " statement.");
                }
            }
        }
    }

    @Override
    public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
        return delegate.createCacheKey(ms, parameterObject, rowBounds, boundSql);
    }

    @Override
    public boolean isCached(MappedStatement ms, CacheKey key) {
        return delegate.isCached(ms, key);
    }

    @Override
    public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
        delegate.deferLoad(ms, resultObject, property, key, targetType);
    }

    @Override
    public void clearLocalCache() {
        delegate.clearLocalCache();
    }

    private void flushCacheIfRequired(MappedStatement ms) {
        Cache cache = ms.getCache();
        if (cache != null && ms.isFlushCacheRequired()) {
            tcm.clear(cache);
        }
    }

    @Override
    public void setExecutorWrapper(Executor executor) {
        throw new UnsupportedOperationException("This method should not be called");
    }

}