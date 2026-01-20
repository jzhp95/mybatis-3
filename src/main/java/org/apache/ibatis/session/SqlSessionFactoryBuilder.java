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
package org.apache.ibatis.session;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;

import org.apache.ibatis.builder.xml.XMLConfigBuilder;
import org.apache.ibatis.exceptions.ExceptionFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;

/**
 * Builds {@link SqlSession} instances.
 *
 * @author Clinton Begin
 */
public class SqlSessionFactoryBuilder {

    public SqlSessionFactory build(Reader reader) {
        return build(reader, null, null);
    }

    public SqlSessionFactory build(Reader reader, String environment) {
        return build(reader, environment, null);
    }

    public SqlSessionFactory build(Reader reader, Properties properties) {
        return build(reader, null, properties);
    }

    /**
     * 使用Reader、环境ID和属性构建SqlSessionFactory
     * 
     * @param reader MyBatis配置文件的字符流
     * @param environment 要使用的环境ID，可为null表示使用默认环境
     * @param properties 可选的属性配置，可用于替换配置文件中的占位符
     * @return 构建完成的SqlSessionFactory实例
     * @throws Exception 当解析配置文件或构建SqlSessionFactory时发生错误
     */
    public SqlSessionFactory build(Reader reader, String environment, Properties properties) {
        try {
            // 初始化 XMLConfigBuilder，完成 Configuration 对象的创建
            // 创建 XPathParser 解析器
            XMLConfigBuilder parser = new XMLConfigBuilder(reader, environment, properties);

            // 解析 settings.xml 配置文件，填充 Configuration 对象
            Configuration configuration = parser.parse();
            // 根据 Configuration 对象创建 DefaultSqlSessionFactory
            return build(configuration);
        } catch (Exception e) {
            // 捕获异常并包装成MyBatis异常，提供统一的错误信息格式
            throw ExceptionFactory.wrapException("Error building SqlSession.", e);
        } finally {
            // 重置错误上下文，确保线程安全
            ErrorContext.instance().reset();
            try {
                // 关闭配置文件输入流，释放资源
                reader.close();
            } catch (IOException e) {
                // Intentionally ignore. Prefer previous error.
                // 故意忽略此异常，优先保留之前的异常信息
            }
        }
    }

    public SqlSessionFactory build(InputStream inputStream) {
        return build(inputStream, null, null);
    }

    public SqlSessionFactory build(InputStream inputStream, String environment) {
        return build(inputStream, environment, null);
    }

    public SqlSessionFactory build(InputStream inputStream, Properties properties) {
        return build(inputStream, null, properties);
    }

    public SqlSessionFactory build(InputStream inputStream, String environment, Properties properties) {
        try {
            XMLConfigBuilder parser = new XMLConfigBuilder(inputStream, environment, properties);
            return build(parser.parse());
        } catch (Exception e) {
            throw ExceptionFactory.wrapException("Error building SqlSession.", e);
        } finally {
            ErrorContext.instance().reset();
            try {
                inputStream.close();
            } catch (IOException e) {
                // Intentionally ignore. Prefer previous error.
            }
        }
    }

    public SqlSessionFactory build(Configuration config) {
        return new DefaultSqlSessionFactory(config);
    }

}