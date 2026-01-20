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
package org.apache.ibatis.transaction;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 包装数据库连接的事务接口
 * <p>
 * 该接口定义了MyBatis事务管理的核心功能，负责处理数据库连接的完整生命周期，
 * 包括：连接的创建、准备、提交/回滚和关闭操作。
 * </p>
 * <p>
 * MyBatis通过实现此接口来支持不同的事务管理方式，如JDBC原生事务、
 * JTA分布式事务以及托管事务等。不同的实现类可以根据具体需求
 * 提供不同的事务隔离级别和事务传播行为。
 * </p>
 * <p>
 * 事务生命周期：
 * 1. 创建事务对象并获取数据库连接
 * 2. 执行SQL操作（在事务范围内）
 * 3. 提交事务（成功）或回滚事务（失败）
 * 4. 关闭数据库连接，释放资源
 * </p>
 *
 * @author Clinton Begin
 * @see org.apache.ibatis.transaction.jdbc.JdbcTransaction JDBC事务实现
 * @see org.apache.ibatis.transaction.managed.ManagedTransaction 托管事务实现
 * @see org.apache.ibatis.transaction.TransactionFactory 事务工厂接口
 */
public interface Transaction {

    /**
     * 获取内部数据库连接
     * <p>
     * 返回当前事务对象管理的数据库连接实例，该连接可用于执行SQL语句。
     * 连接的自动提交状态通常由事务实现类控制，以确保事务的原子性。
     * </p>
     *
     * @return 数据库连接对象
     * @throws SQLException 如果获取连接时发生数据库访问错误
     */
    Connection getConnection() throws SQLException;

    /**
     * 提交当前事务
     * <p>
     * 将自事务开始以来的所有数据库操作永久保存到数据库中。
     * 成功提交后，事务中的所有更改将对其他事务可见。
     * 如果事务已经完成或连接已关闭，可能会抛出异常。
     * </p>
     *
     * @throws SQLException 如果提交过程中发生数据库访问错误
     */
    void commit() throws SQLException;

    /**
     * 回滚当前事务
     * <p>
     * 撤销自事务开始以来的所有数据库操作，使数据库恢复到事务开始前的状态。
     * 通常在发生异常或业务逻辑需要取消所有更改时调用此方法。
     * 如果事务已经完成或连接已关闭，可能会抛出异常。
     * </p>
     *
     * @throws SQLException 如果回滚过程中发生数据库访问错误
     */
    void rollback() throws SQLException;

    /**
     * 关闭数据库连接
     * <p>
     * 释放当前事务对象管理的数据库连接资源。
     * 调用此方法后，连接将不再可用，尝试使用该连接执行操作将导致异常。
     * 通常在事务完成（提交或回滚）后调用此方法。
     * </p>
     *
     * @throws SQLException 如果关闭连接时发生数据库访问错误
     */
    void close() throws SQLException;

    /**
     * 获取事务超时时间（如果已设置）
     * <p>
     * 返回当前事务的超时时间（以秒为单位），超过此时间事务将自动回滚。
     * 如果未设置超时时间，则返回null。超时设置通常由事务管理器
     * 或配置文件指定，用于防止长时间运行的事务占用系统资源。
     * </p>
     *
     * @return 事务超时时间（秒），如果未设置则返回null
     * @throws SQLException 如果获取超时设置时发生数据库访问错误
     */
    Integer getTimeout() throws SQLException;

}
