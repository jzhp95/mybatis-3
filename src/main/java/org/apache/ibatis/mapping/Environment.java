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
package org.apache.ibatis.mapping;

import javax.sql.DataSource;

import org.apache.ibatis.transaction.TransactionFactory;

/**
 * @author Clinton Begin
 */
public final class Environment {
    private final String id;
    private final TransactionFactory transactionFactory;
    private final DataSource dataSource;

    /**
     * 创建Environment实例的构造方法
     * <p>
     * Environment类表示MyBatis的运行环境，包含环境ID、事务管理器和数据源。
     * 每个Environment实例代表一个完整的数据库环境配置，MyBatis可以配置多个环境，
     * 但在运行时只能选择其中一个作为活动环境。
     *
     * @param id                 环境的唯一标识符，用于区分不同的环境配置
     * @param transactionFactory 事务管理器工厂，负责创建和管理事务
     * @param dataSource         数据源，负责获取数据库连接
     * @throws IllegalArgumentException 如果任何必需参数为null
     */
    public Environment(String id, TransactionFactory transactionFactory, DataSource dataSource) {
        // 检查id参数是否为null，为null则抛出IllegalArgumentException异常
        if (id == null) {
            throw new IllegalArgumentException("Parameter 'id' must not be null");
        }
        // 检查transactionFactory参数是否为null，为null则抛出IllegalArgumentException异常
        if (transactionFactory == null) {
            throw new IllegalArgumentException("Parameter 'transactionFactory' must not be null");
        }
        // 设置id字段值
        this.id = id;
        // 检查dataSource参数是否为null，为null则抛出IllegalArgumentException异常
        if (dataSource == null) {
            throw new IllegalArgumentException("Parameter 'dataSource' must not be null");
        }
        // 设置transactionFactory字段值
        this.transactionFactory = transactionFactory;
        // 设置dataSource字段值
        this.dataSource = dataSource;
    }


    public static class Builder {
        private String id;
        private TransactionFactory transactionFactory;
        private DataSource dataSource;

        public Builder(String id) {
            this.id = id;
        }

        public Builder transactionFactory(TransactionFactory transactionFactory) {
            this.transactionFactory = transactionFactory;
            return this;
        }

        public Builder dataSource(DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        public String id() {
            return this.id;
        }

        public Environment build() {
            return new Environment(this.id, this.transactionFactory, this.dataSource);
        }

    }

    public String getId() {
        return this.id;
    }

    public TransactionFactory getTransactionFactory() {
        return this.transactionFactory;
    }

    public DataSource getDataSource() {
        return this.dataSource;
    }

}
