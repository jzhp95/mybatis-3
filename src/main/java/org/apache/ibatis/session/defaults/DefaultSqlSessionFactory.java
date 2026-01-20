/**
 * Copyright 2009-2015 the original author or authors.
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
package org.apache.ibatis.session.defaults;

import java.sql.Connection;
import java.sql.SQLException;

import org.apache.ibatis.exceptions.ExceptionFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;

/**
 * @author Clinton Begin
 */
public class DefaultSqlSessionFactory implements SqlSessionFactory {

    private final Configuration configuration;

    public DefaultSqlSessionFactory(Configuration configuration) {
        this.configuration = configuration;
    }

    @Override
    public SqlSession openSession() {
        return openSessionFromDataSource(configuration.getDefaultExecutorType(), null, false);
    }

    @Override
    public SqlSession openSession(boolean autoCommit) {
        return openSessionFromDataSource(configuration.getDefaultExecutorType(), null, autoCommit);
    }

    @Override
    public SqlSession openSession(ExecutorType execType) {
        return openSessionFromDataSource(execType, null, false);
    }

    @Override
    public SqlSession openSession(TransactionIsolationLevel level) {
        return openSessionFromDataSource(configuration.getDefaultExecutorType(), level, false);
    }

    @Override
    public SqlSession openSession(ExecutorType execType, TransactionIsolationLevel level) {
        return openSessionFromDataSource(execType, level, false);
    }

    @Override
    public SqlSession openSession(ExecutorType execType, boolean autoCommit) {
        return openSessionFromDataSource(execType, null, autoCommit);
    }

    @Override
    public SqlSession openSession(Connection connection) {
        return openSessionFromConnection(configuration.getDefaultExecutorType(), connection);
    }

    @Override
    public SqlSession openSession(ExecutorType execType, Connection connection) {
        return openSessionFromConnection(execType, connection);
    }

    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    /**
     * 从数据源创建并打开一个新的 SqlSession
     * <p>
     * 这是 MyBatis 会话管理的核心方法，负责：
     * 1. 获取环境配置和事务工厂
     * 2. 创建新的事务
     * 3. 创建执行器 (Executor)
     * 4. 构建并返回 DefaultSqlSession 实例
     * <p>
     * 执行流程：
     * 1. 从配置中获取环境信息（Environment）
     * 2. 根据环境获取事务工厂（TransactionFactory）
     * 3. 使用事务工厂创建新的事务（Transaction）
     * 4. 通过配置创建对应的执行器（Executor）
     * 5. 使用配置、执行器和自动提交标志构建 DefaultSqlSession
     * 6. 异常处理：关闭已获取的事务连接，包装并抛出异常
     * 7. 最终清理：重置错误上下文
     * <p>
     * 设计说明：
     * - 事务对象在 try 块外声明，确保在异常时能够正确关闭
     * - 使用 ErrorContext 管理错误上下文，确保线程安全
     * - 异常包装使用 ExceptionFactory，提供统一的异常处理机制
     *
     * @param execType   执行器类型，决定 SQL 执行的方式（SIMPLE/REUSE/BATCH）
     * @param level      事务隔离级别，可为 null 表示使用默认级别
     * @param autoCommit 是否自动提交事务
     * @return 新创建的 SqlSession 实例
     * @throws Exception 如果创建过程中发生任何异常，会包装成 MyBatis 异常抛出
     */

    private SqlSession openSessionFromDataSource(ExecutorType execType, TransactionIsolationLevel level, boolean autoCommit) {
        Transaction tx = null;
        try {
            // 获取配置中的环境信息，包含数据源和事务工厂配置
            final Environment environment = configuration.getEnvironment();

            // 根据环境获取事务工厂，如果未配置则使用默认的 ManagedTransactionFactory
            final TransactionFactory transactionFactory = getTransactionFactoryFromEnvironment(environment);

            // 创建新的事务，传入数据源、隔离级别和自动提交标志
            tx = transactionFactory.newTransaction(environment.getDataSource(), level, autoCommit);

            // 创建执行器，执行器类型决定了 SQL 执行的方式（SIMPLE/REUSE/BATCH）
            final Executor executor = configuration.newExecutor(tx, execType);

            // 构建并返回 DefaultSqlSession 实例
            return new DefaultSqlSession(configuration, executor, autoCommit);
        } catch (Exception e) {
            // 异常处理：关闭可能已经获取的事务连接
            closeTransaction(tx); // may have fetched a connection so lets call close()
            // 包装异常并抛出，提供统一的错误信息格式
            throw ExceptionFactory.wrapException("Error opening session.  Cause: " + e, e);
        } finally {
            // 最终清理：重置错误上下文，确保线程安全
            ErrorContext.instance().reset();
        }
    }

    private SqlSession openSessionFromConnection(ExecutorType execType, Connection connection) {
        try {
            boolean autoCommit;
            try {
                autoCommit = connection.getAutoCommit();
            } catch (SQLException e) {
                // Failover to true, as most poor drivers
                // or databases won't support transactions
                autoCommit = true;
            }
            final Environment environment = configuration.getEnvironment();
            final TransactionFactory transactionFactory = getTransactionFactoryFromEnvironment(environment);
            final Transaction tx = transactionFactory.newTransaction(connection);
            final Executor executor = configuration.newExecutor(tx, execType);
            return new DefaultSqlSession(configuration, executor, autoCommit);
        } catch (Exception e) {
            throw ExceptionFactory.wrapException("Error opening session.  Cause: " + e, e);
        } finally {
            ErrorContext.instance().reset();
        }
    }

    private TransactionFactory getTransactionFactoryFromEnvironment(Environment environment) {
        if (environment == null || environment.getTransactionFactory() == null) {
            return new ManagedTransactionFactory();
        }
        return environment.getTransactionFactory();
    }

    private void closeTransaction(Transaction tx) {
        if (tx != null) {
            try {
                tx.close();
            } catch (SQLException ignore) {
                // Intentionally ignore. Prefer previous error.
            }
        }
    }

}