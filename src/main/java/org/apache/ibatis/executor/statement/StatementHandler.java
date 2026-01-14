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
package org.apache.ibatis.executor.statement;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.ResultHandler;

/**
 * 语句处理器接口，负责JDBC Statement的创建、参数设置和SQL执行
 * 
 * StatementHandler是MyBatis执行SQL的核心组件之一，它封装了JDBC Statement的完整生命周期管理，
 * 包括Statement的创建、参数设置、SQL执行和结果处理。该接口是MyBatis插件系统的重要拦截点，
 * 允许开发者通过插件拦截和修改SQL执行行为。
 * 
 * 设计职责：
 * 1. Statement创建：根据不同的SQL类型创建相应的Statement对象（Statement、PreparedStatement或CallableStatement）
 * 2. 参数设置：将Java对象参数设置到SQL语句中
 * 3. SQL执行：执行增删改查操作
 * 4. 结果处理：将JDBC结果集转换为Java对象
 * 
 * 实现类：
 * - RoutingStatementHandler：路由语句处理器，根据SQL类型路由到不同的具体实现
 * - PreparedStatementHandler：预处理语句处理器，处理参数化SQL
 * - CallableStatementHandler：调用语句处理器，处理存储过程
 * - SimpleStatementHandler：简单语句处理器，处理无参数SQL
 * 
 * 插件支持：
 * StatementHandler是MyBatis四大核心对象之一（Executor、StatementHandler、ParameterHandler、ResultSetHandler），
 * 可以通过@Intercepts注解进行拦截，实现SQL监控、性能分析、权限控制等功能。
 * 
 * @author Clinton Begin
 */
public interface StatementHandler {

    /**
     * 准备Statement对象，包括创建和初始化
     * 
     * 此方法负责根据给定的数据库连接创建JDBC Statement对象，并进行必要的初始化设置。
     * 根据不同的实现，可能会创建Statement、PreparedStatement或CallableStatement。
     * 
     * @param connection 数据库连接对象，用于创建Statement
     * @param transactionTimeout 事务超时时间（秒），可能为null
     * @return 初始化完成的Statement对象
     * @throws SQLException 如果创建Statement过程中发生数据库错误
     */
    Statement prepare(Connection connection, Integer transactionTimeout)
            throws SQLException;

    /**
     * 设置Statement参数
     * 
     * 此方法负责将Java对象参数设置到SQL语句中，对于PreparedStatement和CallableStatement尤为重要。
     * 参数设置过程包括类型转换、空值处理和特殊字符转义等。
     * 
     * @param statement 已创建的Statement对象，需要设置参数
     * @throws SQLException 如果参数设置过程中发生数据库错误
     */
    void parameterize(Statement statement)
            throws SQLException;

    /**
     * 批量添加SQL到Statement中
     * 
     * 此方法用于批量操作，将SQL语句添加到批处理队列中，但不立即执行。
     * 适用于需要执行大量相似SQL语句的场景，可以提高性能。
     * 
     * @param statement Statement对象，用于批处理
     * @throws SQLException 如果添加批处理过程中发生数据库错误
     */
    void batch(Statement statement)
            throws SQLException;

    /**
     * 执行更新操作（INSERT、UPDATE、DELETE）
     * 
     * 此方法负责执行更新类型的SQL语句，并返回受影响的行数。
     * 更新操作会修改数据库中的数据，但不返回结果集。
     * 
     * @param statement Statement对象，用于执行更新操作
     * @return 受影响的行数
     * @throws SQLException 如果执行更新过程中发生数据库错误
     */
    int update(Statement statement)
            throws SQLException;

    /**
     * 执行查询操作（SELECT）
     * 
     * 此方法负责执行查询类型的SQL语句，并将结果集转换为Java对象列表。
     * 查询操作不会修改数据库数据，但可能返回大量数据。
     * 
     * @param <E> 返回列表的元素类型
     * @param statement Statement对象，用于执行查询操作
     * @param resultHandler 结果处理器，用于自定义结果处理逻辑，可能为null
     * @return 查询结果列表
     * @throws SQLException 如果执行查询过程中发生数据库错误
     */
    <E> List<E> query(Statement statement, ResultHandler resultHandler)
            throws SQLException;

    /**
     * 执行查询操作（SELECT），返回游标结果
     * 
     * 此方法与query方法类似，但返回Cursor对象而非List，适用于处理大量数据的场景。
     * 游标结果支持惰性加载，可以减少内存消耗，提高大数据量查询的性能。
     * 
     * @param <E> 游标返回的元素类型
     * @param statement Statement对象，用于执行查询操作
     * @return 查询结果的游标对象
     * @throws SQLException 如果执行查询过程中发生数据库错误
     */
    <E> Cursor<E> queryCursor(Statement statement)
            throws SQLException;

    /**
     * 获取绑定SQL对象
     * 
     * 返回当前StatementHandler关联的BoundSql对象，包含完整的SQL语句和参数映射信息。
     * BoundSql是MyBatis中SQL语句的运行时表示，包含了参数化后的SQL和参数映射关系。
     * 
     * @return 绑定的SQL对象
     */
    BoundSql getBoundSql();

    /**
     * 获取参数处理器
     * 
     * 返回当前StatementHandler关联的ParameterHandler对象，负责参数的设置和转换。
     * ParameterHandler是MyBatis四大核心对象之一，专门处理Java对象到JDBC参数的转换。
     * 
     * @return 参数处理器对象
     */
    ParameterHandler getParameterHandler();

}