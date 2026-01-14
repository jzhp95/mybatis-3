/**
 * Copyright 2009-2016 the original author or authors.
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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * @author Clinton Begin
 */
public class SimpleExecutor extends BaseExecutor {

    public SimpleExecutor(Configuration configuration, Transaction transaction) {
        super(configuration, transaction);
    }

    @Override
    public int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
        Statement stmt = null;
        try {
            Configuration configuration = ms.getConfiguration();
            StatementHandler handler = configuration.newStatementHandler(this, ms, parameter, RowBounds.DEFAULT, null, null);
            stmt = prepareStatement(handler, ms.getStatementLog());
            return handler.update(stmt);
        } finally {
            closeStatement(stmt);
        }
    }

    /**
     * 执行查询操作，是SimpleExecutor的核心实现方法
     * <p>
     * 此方法实现了BaseExecutor抽象类中定义的doQuery方法，负责实际执行数据库查询。
     * SimpleExecutor是MyBatis中最简单的执行器实现，每次执行SQL都会创建一个新的Statement对象，
     * 执行完毕后立即关闭，不进行Statement的复用。
     * <p>
     * 执行流程：
     * 1. 从MappedStatement获取Configuration配置对象
     * 2. 创建StatementHandler处理器，负责SQL语句的预处理和参数设置
     * 3. 准备Statement对象，包括创建连接、预编译SQL和设置参数
     * 4. 通过StatementHandler执行查询并返回结果
     * 5. 在finally块中确保Statement被正确关闭，防止资源泄露
     * <p>
     * 设计特点：
     * - 简单直接：每次查询都创建新的Statement，保证查询之间的隔离性
     * - 资源安全：通过try-finally确保Statement资源被正确释放
     * - 职责分离：将SQL处理、参数设置和结果处理委托给专门的组件
     *
     * @param <E>           返回列表的元素类型
     * @param ms            映射语句对象，包含SQL语句和配置信息
     * @param parameter     SQL参数对象，可能是基本类型、Map或自定义POJO
     * @param rowBounds     行边界限制，用于分页查询
     * @param resultHandler 结果处理器，用于处理结果集
     * @param boundSql      绑定的SQL对象，包含SQL语句和参数映射信息
     * @return 查询结果列表
     * @throws SQLException 如果数据库访问过程中发生错误
     */
    @Override
    public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
        Statement stmt = null;
        try {
            // 获取全局配置对象，包含MyBatis的所有配置信息
            Configuration configuration = ms.getConfiguration();

            // 创建StatementHandler处理器，这是MyBatis的核心组件之一
            // 负责JDBC Statement的创建、参数设置和SQL执行
            // wrapper参数用于支持插件拦截机制
            StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler, boundSql);

            // 准备Statement对象，包括：
            // 1. 获取数据库连接
            // 2. 创建或预编译Statement
            // 3. 设置SQL参数
            stmt = prepareStatement(handler, ms.getStatementLog());

            // 执行查询并返回结果
            // StatementHandler会根据不同的SQL类型调用相应的查询方法
            // 并将结果集映射为Java对象
            return handler.<E>query(stmt, resultHandler);
        } finally {
            // 确保Statement被正确关闭，防止资源泄露
            // 这是非常重要的资源管理步骤，即使查询过程中发生异常
            closeStatement(stmt);
        }
    }

    @Override
    protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql) throws SQLException {
        Configuration configuration = ms.getConfiguration();
        StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
        Statement stmt = prepareStatement(handler, ms.getStatementLog());
        return handler.<E>queryCursor(stmt);
    }

    @Override
    public List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
        return Collections.emptyList();
    }

    /**
     * 准备JDBC Statement对象，用于执行SQL语句。
     * <p>
     * 此方法是SimpleExecutor执行SQL前的关键准备工作，负责创建和配置Statement对象。
     * 它通过StatementHandler完成Statement的创建、参数设置等核心操作，是MyBatis
     * 执行器与JDBC Statement之间的桥梁。
     * <p>
     * 执行流程：
     * 1. 获取数据库连接，可能应用日志记录功能
     * 2. 通过StatementHandler创建Statement对象，设置事务超时时间
     * 3. 调用StatementHandler的parameterize方法设置SQL参数
     * 4. 返回准备就绪的Statement对象
     * <p>
     * 设计特点：
     * - 职责分离：将Statement的创建和参数设置委托给StatementHandler
     * - 事务支持：通过transaction.getTimeout()设置事务超时
     * - 日志集成：支持SQL执行日志记录，便于调试和性能分析
     * - 异常传播：保持SQLException的原始异常信息
     *
     * @param handler StatementHandler实例，负责Statement的创建和参数设置
     * @param statementLog 日志记录器，用于记录SQL执行信息
     * @return 准备就绪的Statement对象，已设置参数和事务超时
     * @throws SQLException 如果获取连接、创建Statement或设置参数过程中发生错误
     * @see StatementHandler#prepare(Connection, Integer)
     * @see StatementHandler#parameterize(Statement)
     * @see BaseExecutor#getConnection(Log)
     */
    private Statement prepareStatement(StatementHandler handler, Log statementLog) throws SQLException {
        Statement stmt;
        // 获取数据库连接，如果启用了调试日志，则返回一个带日志功能的连接代理
        Connection connection = getConnection(statementLog);
        
        // 通过StatementHandler创建Statement对象，并设置事务超时时间
        // StatementHandler会根据SQL类型创建相应的Statement：
        // - 普通SQL: 创建Statement
        // - 预编译SQL: 创建PreparedStatement
        // - 存储过程: 创建CallableStatement
        stmt = handler.prepare(connection, transaction.getTimeout());
        
        // 设置SQL参数，将Java对象映射为JDBC参数
        // StatementHandler会调用ParameterHandler完成参数设置
        handler.parameterize(stmt);
        
        return stmt;
    }

}