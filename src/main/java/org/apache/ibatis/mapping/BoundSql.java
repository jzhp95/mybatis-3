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
package org.apache.ibatis.mapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.session.Configuration;

/**
 * 绑定SQL类，表示从SqlSource获取的已处理动态内容的实际SQL字符串。
 *
 * <p>该类包含了SQL语句执行所需的所有信息，包括SQL语句本身、参数映射、参数对象等。
 * SQL可能包含SQL占位符"?"和有序的参数映射列表，每个参数映射包含每个参数的附加信息
 * （至少包括输入对象的属性名，用于读取值）。</p>
 *
 * <p>此外，还可以包含由动态语言（如循环、bind等）创建的附加参数。</p>
 *
 * <p>主要功能：</p>
 * <ul>
 * <li>存储SQL语句和参数映射信息</li>
 * <li>管理参数对象和附加参数</li>
 * <li>提供参数访问和设置方法</li>
 * </ul>
 *
 * @author Clinton Begin
 */
public class BoundSql {

    /**
     * SQL语句字符串，可能包含占位符"?"
     */
    private final String sql;
    /**
     * 参数映射列表，按顺序记录每个参数的映射信息
     */
    private final List<ParameterMapping> parameterMappings;
    /**
     * 原始参数对象，包含SQL执行所需的参数值
     */
    private final Object parameterObject;
    /**
     * 附加参数映射，存储动态语言生成的额外参数
     */
    private final Map<String, Object> additionalParameters;
    /**
     * 附加参数的MetaObject，用于方便地访问和设置附加参数
     */
    private final MetaObject metaParameters;

    /**
     * 构造一个BoundSql实例。
     *
     * @param configuration     MyBatis配置对象
     * @param sql               SQL语句字符串
     * @param parameterMappings 参数映射列表
     * @param parameterObject   参数对象
     */
    public BoundSql(Configuration configuration, String sql, List<ParameterMapping> parameterMappings, Object parameterObject) {
        this.sql = sql;
        this.parameterMappings = parameterMappings;
        this.parameterObject = parameterObject;
        // 初始化附加参数映射
        this.additionalParameters = new HashMap<String, Object>();
        // 创建附加参数的MetaObject，便于操作
        this.metaParameters = configuration.newMetaObject(additionalParameters);
    }

    /**
     * 获取SQL语句。
     *
     * @return SQL语句字符串
     */
    public String getSql() {
        return sql;
    }

    /**
     * 获取参数映射列表。
     *
     * @return 参数映射列表
     */
    public List<ParameterMapping> getParameterMappings() {
        return parameterMappings;
    }

    /**
     * 获取参数对象。
     *
     * @return 参数对象
     */
    public Object getParameterObject() {
        return parameterObject;
    }

    /**
     * 检查是否存在指定的附加参数。
     *
     * @param name 参数名
     * @return 如果存在返回true，否则返回false
     */
    public boolean hasAdditionalParameter(String name) {
        // 使用PropertyTokenizer解析参数名，获取根属性名
        String paramName = new PropertyTokenizer(name).getName();
        return additionalParameters.containsKey(paramName);
    }

    /**
     * 设置附加参数的值。
     *
     * @param name  参数名
     * @param value 参数值
     */
    public void setAdditionalParameter(String name, Object value) {
        // 通过MetaObject设置参数值，支持嵌套属性
        metaParameters.setValue(name, value);
    }

    /**
     * 获取附加参数的值。
     *
     * @param name 参数名
     * @return 参数值
     */
    public Object getAdditionalParameter(String name) {
        // 通过MetaObject获取参数值，支持嵌套属性
        return metaParameters.getValue(name);
    }
}