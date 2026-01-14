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
package org.apache.ibatis.scripting.defaults;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeException;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class DefaultParameterHandler implements ParameterHandler {

    private final TypeHandlerRegistry typeHandlerRegistry;

    private final MappedStatement mappedStatement;
    private final Object parameterObject;
    private final BoundSql boundSql;
    private final Configuration configuration;

    public DefaultParameterHandler(MappedStatement mappedStatement, Object parameterObject, BoundSql boundSql) {
        this.mappedStatement = mappedStatement;
        this.configuration = mappedStatement.getConfiguration();
        this.typeHandlerRegistry = mappedStatement.getConfiguration().getTypeHandlerRegistry();
        this.parameterObject = parameterObject;
        this.boundSql = boundSql;
    }

    @Override
    public Object getParameterObject() {
        return parameterObject;
    }

    /**
     * 设置PreparedStatement的参数值，将Java对象转换为JDBC参数。
     * <p>
     * 此方法是MyBatis参数处理的核心实现，负责将Java对象中的属性值设置到PreparedStatement中。
     * 它通过TypeHandler系统完成Java类型到JDBC类型的转换，是MyBatis参数绑定的关键环节。
     * <p>
     * 执行流程：
     * 1. 设置错误上下文，便于异常追踪和调试
     * 2. 获取参数映射列表，遍历每个参数映射
     * 3. 跳过OUT模式的参数（仅用于存储过程输出参数）
     * 4. 根据参数名称获取参数值，支持多种获取方式
     * 5. 获取参数的TypeHandler和JdbcType
     * 6. 处理null值的JdbcType设置
     * 7. 通过TypeHandler将参数值设置到PreparedStatement中
     * <p>
     * 参数值获取策略：
     * - 优先从BoundSql的额外参数中获取（解决issue #448）
     * - 如果参数对象为null，则值为null
     * - 如果参数对象有TypeHandler，则直接使用参数对象
     * - 否则通过反射获取参数对象中对应属性的值
     * <p>
     * 异常处理：
     * - 捕获TypeException和SQLException，转换为更具体的TypeException
     * - 保留原始异常信息，便于问题定位
     *
     * @param ps 需要设置参数的PreparedStatement对象
     * @throws TypeException 如果参数设置过程中发生类型转换错误
     * @see ParameterMapping
     * @see TypeHandler
     * @see BoundSql#getParameterMappings()
     * @see BoundSql#hasAdditionalParameter(String)
     */
    @Override
    public void setParameters(PreparedStatement ps) {
        // 设置错误上下文，便于异常追踪和调试
        ErrorContext.instance().activity("setting parameters").object(mappedStatement.getParameterMap().getId());
        
        // 获取参数映射列表，包含所有需要设置的参数信息
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        
        if (parameterMappings != null) {
            // 遍历每个参数映射，设置对应的参数值
            for (int i = 0; i < parameterMappings.size(); i++) {
                ParameterMapping parameterMapping = parameterMappings.get(i);
                
                // 跳过OUT模式的参数，这些参数仅用于存储过程的输出参数
                if (parameterMapping.getMode() != ParameterMode.OUT) {
                    Object value;
                    String propertyName = parameterMapping.getProperty();
                    
                    // 获取参数值，按优先级顺序尝试多种获取方式
                    if (boundSql.hasAdditionalParameter(propertyName)) { 
                        // 优先从BoundSql的额外参数中获取（解决issue #448）
                        value = boundSql.getAdditionalParameter(propertyName);
                    } else if (parameterObject == null) {
                        // 如果参数对象为null，则值为null
                        value = null;
                    } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
                        // 如果参数对象有TypeHandler，则直接使用参数对象
                        // 这种情况通常发生在参数对象本身就是简单类型（如String、Integer等）
                        value = parameterObject;
                    } else {
                        // 否则通过反射获取参数对象中对应属性的值
                        MetaObject metaObject = configuration.newMetaObject(parameterObject);
                        value = metaObject.getValue(propertyName);
                    }
                    
                    // 获取参数的TypeHandler和JdbcType
                    TypeHandler typeHandler = parameterMapping.getTypeHandler();
                    JdbcType jdbcType = parameterMapping.getJdbcType();
                    
                    // 处理null值的JdbcType设置
                    // 如果值为null且未指定JdbcType，则使用配置中指定的null值JdbcType
                    if (value == null && jdbcType == null) {
                        jdbcType = configuration.getJdbcTypeForNull();
                    }
                    
                    try {
                        // 通过TypeHandler将参数值设置到PreparedStatement中
                        // 参数索引从1开始，所以使用i+1
                        typeHandler.setParameter(ps, i + 1, value, jdbcType);
                    } catch (TypeException e) {
                        // 捕获类型转换异常，转换为更具体的TypeException
                        throw new TypeException("Could not set parameters for mapping: " + parameterMapping + ". Cause: " + e, e);
                    } catch (SQLException e) {
                        // 捕获SQL异常，转换为TypeException
                        throw new TypeException("Could not set parameters for mapping: " + parameterMapping + ". Cause: " + e, e);
                    }
                }
            }
        }
    }

}