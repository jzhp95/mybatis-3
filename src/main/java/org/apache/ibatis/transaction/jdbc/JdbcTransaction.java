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
package org.apache.ibatis.transaction.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.TransactionException;

/**
 * {@link Transaction} that makes use of the JDBC commit and rollback facilities directly.
 * It relies on the connection retrieved from the dataSource to manage the scope of the transaction.
 * Delays connection retrieval until getConnection() is called.
 * Ignores commit or rollback requests when autocommit is on.
 *
 * @author Clinton Begin
 * @see JdbcTransactionFactory
 */
public class JdbcTransaction implements Transaction {

    // 日志记录器，用于记录JdbcTransaction的操作日志
    private static final Log log = LogFactory.getLog(JdbcTransaction.class);

    // 数据库连接对象，用于执行SQL操作
    protected Connection connection;
    // 数据源，用于获取数据库连接
    protected DataSource dataSource;
    // 事务隔离级别，定义事务的隔离程度
    protected TransactionIsolationLevel level;
    // MEMO: We are aware of the typo. See #941
    // 自动提交标志，注意这里的拼写错误是已知的，见issue #941
    protected boolean autoCommmit;

    /**
     * 构造函数，使用数据源、事务隔离级别和自动提交标志创建JdbcTransaction实例
     *
     * @param ds                数据源，用于获取数据库连接
     * @param desiredLevel      期望的事务隔离级别
     * @param desiredAutoCommit 期望的自动提交设置
     */
    public JdbcTransaction(DataSource ds, TransactionIsolationLevel desiredLevel, boolean desiredAutoCommit) {
        // 初始化数据源
        dataSource = ds;
        // 初始化事务隔离级别
        level = desiredLevel;
        // 初始化自动提交标志
        autoCommmit = desiredAutoCommit;
    }

    /**
     * 构造函数，使用现有的数据库连接创建JdbcTransaction实例
     *
     * @param connection 现有的数据库连接
     */
    public JdbcTransaction(Connection connection) {
        // 设置数据库连接
        this.connection = connection;
    }

    /**
     * 获取数据库连接
     * 如果连接为null，则打开新连接
     *
     * @return 数据库连接对象
     * @throws SQLException 如果获取连接时发生错误
     */
    @Override
    public Connection getConnection() throws SQLException {
        // 检查连接是否为null
        if (connection == null) {
            // 如果为null，则打开新连接
            openConnection();
        }
        // 返回连接对象
        return connection;
    }

    /**
     * 提交事务
     * 只有在连接不为null且自动提交为false时才执行提交操作
     *
     * @throws SQLException 如果提交时发生错误
     */
    @Override
    public void commit() throws SQLException {
        // 检查连接是否存在且自动提交为false
        if (connection != null && !connection.getAutoCommit()) {
            // 如果日志级别为DEBUG，记录提交日志
            if (log.isDebugEnabled()) {
                log.debug("Committing JDBC Connection [" + connection + "]");
            }
            // 执行提交操作
            connection.commit();
        }
    }

    /**
     * 回滚事务
     * 只有在连接不为null且自动提交为false时才执行回滚操作
     *
     * @throws SQLException 如果回滚时发生错误
     */
    @Override
    public void rollback() throws SQLException {
        // 检查连接是否存在且自动提交为false
        if (connection != null && !connection.getAutoCommit()) {
            // 如果日志级别为DEBUG，记录回滚日志
            if (log.isDebugEnabled()) {
                log.debug("Rolling back JDBC Connection [" + connection + "]");
            }
            // 执行回滚操作
            connection.rollback();
        }
    }

    /**
     * 关闭连接
     * 在关闭前重置自动提交设置
     *
     * @throws SQLException 如果关闭连接时发生错误
     */
    @Override
    public void close() throws SQLException {
        // 检查连接是否存在
        if (connection != null) {
            // 重置自动提交设置
            resetAutoCommit();
            // 如果日志级别为DEBUG，记录关闭日志
            if (log.isDebugEnabled()) {
                log.debug("Closing JDBC Connection [" + connection + "]");
            }
            // 关闭连接
            connection.close();
        }
    }

    /**
     * 设置连接的自动提交模式
     * 只有当当前自动提交设置与期望设置不同时才进行更改
     *
     * @param desiredAutoCommit 期望的自动提交设置
     */
    protected void setDesiredAutoCommit(boolean desiredAutoCommit) {
        try {
            // 检查当前自动提交设置是否与期望设置不同
            if (connection.getAutoCommit() != desiredAutoCommit) {
                // 如果日志级别为DEBUG，记录设置日志
                if (log.isDebugEnabled()) {
                    log.debug("Setting autocommit to " + desiredAutoCommit + " on JDBC Connection [" + connection + "]");
                }
                // 设置自动提交模式
                connection.setAutoCommit(desiredAutoCommit);
            }
        } catch (SQLException e) {
            // Only a very poorly implemented driver would fail here,
            // and there's not much we can do about that.
            // 抛出事务异常，包含详细的错误信息
            throw new TransactionException("Error configuring AutoCommit.  "
                    + "Your driver may not support getAutoCommit() or setAutoCommit(). "
                    + "Requested setting: " + desiredAutoCommit + ".  Cause: " + e, e);
        }
    }

    /**
     * 重置自动提交设置为true
     * 这是为了解决某些数据库在只执行查询操作时需要显式提交或回滚的问题
     */
    protected void resetAutoCommit() {
        try {
            // 检查自动提交是否为false
            if (!connection.getAutoCommit()) {
                // MyBatis does not call commit/rollback on a connection if just selects were performed.
                // Some databases start transactions with select statements
                // and they mandate a commit/rollback before closing the connection.
                // A workaround is setting the autocommit to true before closing the connection.
                // Sybase throws an exception here.
                // 如果日志级别为DEBUG，记录重置日志
                if (log.isDebugEnabled()) {
                    log.debug("Resetting autocommit to true on JDBC Connection [" + connection + "]");
                }
                // 设置自动提交为true
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            // 如果日志级别为DEBUG，记录错误日志
            if (log.isDebugEnabled()) {
                log.debug("Error resetting autocommit to true "
                        + "before closing the connection.  Cause: " + e);
            }
        }
    }

    /**
     * 打开数据库连接
     * 从数据源获取连接，设置事务隔离级别和自动提交模式
     *
     * @throws SQLException 如果打开连接时发生错误
     */
    protected void openConnection() throws SQLException {
        // 如果日志级别为DEBUG，记录打开连接日志
        if (log.isDebugEnabled()) {
            log.debug("Opening JDBC Connection");
        }
        // 从数据源获取连接
        connection = dataSource.getConnection();
        // 如果设置了事务隔离级别
        if (level != null) {
            // 设置连接的事务隔离级别
            connection.setTransactionIsolation(level.getLevel());
        }
        // 设置自动提交模式
        setDesiredAutoCommit(autoCommmit);
    }

    /**
     * 获取事务超时时间
     * JdbcTransaction不实现超时功能，因此返回null
     *
     * @return 始终返回null
     * @throws SQLException 不会抛出此异常
     */
    @Override
    public Integer getTimeout() throws SQLException {
        // 返回null，表示不实现超时功能
        return null;
    }

}

