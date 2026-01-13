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
package org.apache.ibatis.builder.annotation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;

/**
 * SQL提供者源实现类，用于动态生成SQL语句。
 *
 * <p>该类实现了SqlSource接口，通过调用用户定义的SQL提供者方法来动态生成SQL语句。
 * 支持多种参数传递方式，并负责将生成的SQL字符串解析为可执行的SqlSource对象。</p>
 *
 * <p>主要功能：</p>
 * <ul>
 * <li>通过反射调用SQL提供者方法</li>
 * <li>处理多种参数类型和传递方式（单个参数、Map参数、ProviderContext等）</li>
 * <li>解析SQL字符串中的占位符</li>
 * <li>将动态生成的SQL转换为MyBatis可执行的SqlSource对象</li>
 * </ul>
 *
 * <p>使用场景：</p>
 * <ul>
 * <li>当SQL语句需要根据运行时条件动态构建时</li>
 * <li>当需要通过Java代码生成复杂SQL语句时</li>
 * <li>当希望将SQL生成逻辑与Mapper接口分离时</li>
 * </ul>
 *
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class ProviderSqlSource implements SqlSource {

    /**
     * MyBatis配置对象，用于获取全局配置信息
     */
    private final Configuration configuration;
    /**
     * SQL源构建器，用于将SQL字符串解析为SqlSource对象
     */
    private final SqlSourceBuilder sqlSourceParser;
    /**
     * SQL提供者类的类型
     */
    private final Class<?> providerType;
    /**
     * SQL提供者方法，用于生成SQL语句
     */
    private Method providerMethod;
    /**
     * SQL提供者方法的参数名称数组
     */
    private String[] providerMethodArgumentNames;
    /**
     * SQL提供者方法的参数类型数组
     */
    private Class<?>[] providerMethodParameterTypes;
    /**
     * 提供者上下文，包含映射器类型和方法信息
     */
    private ProviderContext providerContext;
    /**
     * 提供者上下文在参数列表中的索引位置
     */
    private Integer providerContextIndex;

    /**
     * @deprecated 请使用 {@link #ProviderSqlSource(Configuration, Object, Class, Method)} 替代此构造方法。
     */
    @Deprecated
    public ProviderSqlSource(Configuration configuration, Object provider) {
        this(configuration, provider, null, null);
    }

    /**
     * 构造一个ProviderSqlSource实例。
     *
     * <p>该构造方法会解析提供者信息，查找匹配的SQL提供者方法，并处理ProviderContext参数。</p>
     *
     * @param configuration MyBatis配置对象
     * @param provider      提供者对象，包含类型和方法信息
     * @param mapperType    映射器接口类型
     * @param mapperMethod  映射器方法
     * @throws BuilderException 当创建SqlSource失败时抛出
     * @since 3.4.5
     */
    public ProviderSqlSource(Configuration configuration, Object provider, Class<?> mapperType, Method mapperMethod) {
        String providerMethodName;
        try {
            // 初始化基本配置
            this.configuration = configuration;
            this.sqlSourceParser = new SqlSourceBuilder(configuration);
            // 通过反射获取提供者类型和方法名
            this.providerType = (Class<?>) provider.getClass().getMethod("type").invoke(provider);
            providerMethodName = (String) provider.getClass().getMethod("method").invoke(provider);

            // 查找匹配的SQL提供者方法
            for (Method m : this.providerType.getMethods()) {
                if (providerMethodName.equals(m.getName()) && CharSequence.class.isAssignableFrom(m.getReturnType())) {
                    if (providerMethod != null) {
                        throw new BuilderException("Error creating SqlSource for SqlProvider. Method '"
                                + providerMethodName + "' is found multiple in SqlProvider '" + this.providerType.getName()
                                + "'. Sql provider method can not overload.");
                    }
                    // 保存找到的方法信息
                    this.providerMethod = m;
                    this.providerMethodArgumentNames = new ParamNameResolver(configuration, m).getNames();
                    this.providerMethodParameterTypes = m.getParameterTypes();
                }
            }
        } catch (BuilderException e) {
            throw e;
        } catch (Exception e) {
            throw new BuilderException("Error creating SqlSource for SqlProvider.  Cause: " + e, e);
        }

        // 检查是否找到了匹配的方法
        if (this.providerMethod == null) {
            throw new BuilderException("Error creating SqlSource for SqlProvider. Method '"
                    + providerMethodName + "' not found in SqlProvider '" + this.providerType.getName() + "'.");
        }

        // 检查并处理ProviderContext参数
        for (int i = 0; i < this.providerMethodParameterTypes.length; i++) {
            Class<?> parameterType = this.providerMethodParameterTypes[i];
            if (parameterType == ProviderContext.class) {
                if (this.providerContext != null) {
                    throw new BuilderException("Error creating SqlSource for SqlProvider. ProviderContext found multiple in SqlProvider method ("
                            + this.providerType.getName() + "." + providerMethod.getName()
                            + "). ProviderContext can not define multiple in SqlProvider method argument.");
                }
                // 创建ProviderContext对象并记录其位置
                this.providerContext = new ProviderContext(mapperType, mapperMethod);
                this.providerContextIndex = i;
            }
        }
    }

    /**
     * 获取绑定SQL对象。
     *
     * <p>该方法通过调用createSqlSource方法创建SqlSource，然后从中获取BoundSql对象。</p>
     *
     * @param parameterObject 参数对象
     * @return 包含SQL语句和参数映射的BoundSql对象
     */
    @Override
    public BoundSql getBoundSql(Object parameterObject) {
        SqlSource sqlSource = createSqlSource(parameterObject);
        return sqlSource.getBoundSql(parameterObject);
    }

    /**
     * 创建SQL源对象，通过调用SQL提供者方法生成SQL语句。
     *
     * <p>该方法负责根据参数对象的不同类型，调用相应的SQL提供者方法，并将返回的SQL字符串
     * 解析为可执行的SqlSource对象。</p>
     *
     * <p>处理流程如下：</p>
     * <ul>
     * <li>确定提供者方法的绑定参数数量（排除ProviderContext参数）</li>
     * <li>根据参数类型和数量，选择合适的方式调用提供者方法</li>
     * <li>解析返回的SQL字符串，替换其中的占位符</li>
     * <li>将解析后的SQL转换为SqlSource对象</li>
     * </ul>
     *
     * <p>支持以下几种调用方式：</p>
     * <ul>
     * <li>无参数方法：直接调用提供者方法</li>
     * <li>只有ProviderContext参数：传入ProviderContext对象</li>
     * <li>单个参数：传入参数对象</li>
     * <li>多个参数：参数对象必须是Map类型</li>
     * </ul>
     *
     * @param parameterObject 参数对象，可能是单个值、Map或POJO
     * @return 解析后的SqlSource对象，包含可执行的SQL语句
     * @throws BuilderException 当调用提供者方法失败或参数类型不匹配时抛出
     */
    private SqlSource createSqlSource(Object parameterObject) {
        try {
            // 计算绑定参数数量（排除ProviderContext参数）
            int bindParameterCount = providerMethodParameterTypes.length - (providerContext == null ? 0 : 1);
            String sql;

            // 根据不同情况调用提供者方法
            if (providerMethodParameterTypes.length == 0) {
                // 无参数方法
                sql = invokeProviderMethod();
            } else if (bindParameterCount == 0) {
                // 只有ProviderContext参数
                sql = invokeProviderMethod(providerContext);
            } else if (bindParameterCount == 1 &&
                    (parameterObject == null || providerMethodParameterTypes[(providerContextIndex == null || providerContextIndex == 1) ? 0 : 1].isAssignableFrom(parameterObject.getClass()))) {
                // 单个参数，且类型匹配
                sql = invokeProviderMethod(extractProviderMethodArguments(parameterObject));
            } else if (parameterObject instanceof Map) {
                // 多个参数，参数对象必须是Map类型
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) parameterObject;
                sql = invokeProviderMethod(extractProviderMethodArguments(params, providerMethodArgumentNames));
            } else {
                // 不支持的参数类型
                throw new BuilderException("Error invoking SqlProvider method ("
                        + providerType.getName() + "." + providerMethod.getName()
                        + "). Cannot invoke a method that holds "
                        + (bindParameterCount == 1 ? "named argument(@Param)" : "multiple arguments")
                        + " using a specifying parameterObject. In this case, please specify a 'java.util.Map' object.");
            }

            // 确定参数类型
            Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();

            // 解析SQL字符串，替换占位符，并创建SqlSource对象
            return sqlSourceParser.parse(replacePlaceholder(sql), parameterType, new HashMap<String, Object>());
        } catch (BuilderException e) {
            // 重新抛出构建异常
            throw e;
        } catch (Exception e) {
            // 包装其他异常为构建异常
            throw new BuilderException("Error invoking SqlProvider method ("
                    + providerType.getName() + "." + providerMethod.getName()
                    + ").  Cause: " + e, e);
        }
    }

    /**
     * 提取单个参数的提供者方法参数数组。
     *
     * <p>该方法处理单个参数的情况，如果有ProviderContext，则将其与参数对象组合成参数数组。</p>
     *
     * @param parameterObject 参数对象
     * @return 参数数组，包含参数对象和可能的ProviderContext
     */
    private Object[] extractProviderMethodArguments(Object parameterObject) {
        if (providerContext != null) {
            // 创建包含两个元素的参数数组
            Object[] args = new Object[2];
            // 根据ProviderContext的位置放置参数对象
            args[providerContextIndex == 0 ? 1 : 0] = parameterObject;
            // 放置ProviderContext
            args[providerContextIndex] = providerContext;
            return args;
        } else {
            // 没有ProviderContext，直接返回包含参数对象的数组
            return new Object[]{parameterObject};
        }
    }

    /**
     * 提取Map参数的提供者方法参数数组。
     *
     * <p>该方法处理多个参数的情况，从Map中根据参数名称提取对应的值，并组合成参数数组。</p>
     *
     * @param params        参数Map，键为参数名，值为参数值
     * @param argumentNames 参数名称数组
     * @return 参数数组，包含从Map中提取的参数值和可能的ProviderContext
     */
    private Object[] extractProviderMethodArguments(Map<String, Object> params, String[] argumentNames) {
        Object[] args = new Object[argumentNames.length];
        for (int i = 0; i < args.length; i++) {
            if (providerContextIndex != null && providerContextIndex == i) {
                // 如果当前位置是ProviderContext，则使用ProviderContext对象
                args[i] = providerContext;
            } else {
                // 从Map中根据参数名获取参数值
                args[i] = params.get(argumentNames[i]);
            }
        }
        return args;
    }

    /**
     * 调用SQL提供者方法。
     *
     * <p>该方法通过反射调用SQL提供者方法，支持静态和实例方法。</p>
     *
     * @param args 方法参数数组
     * @return 生成的SQL字符串
     * @throws Exception 当调用方法失败时抛出
     */
    private String invokeProviderMethod(Object... args) throws Exception {
        Object targetObject = null;
        // 如果不是静态方法，需要创建提供者类的实例
        if (!Modifier.isStatic(providerMethod.getModifiers())) {
            targetObject = providerType.newInstance();
        }
        // 通过反射调用方法
        CharSequence sql = (CharSequence) providerMethod.invoke(targetObject, args);
        return sql != null ? sql.toString() : null;
    }

    /**
     * 替换SQL字符串中的占位符。
     *
     * <p>该方法使用PropertyParser解析SQL字符串中的占位符，并使用配置中的变量进行替换。</p>
     *
     * @param sql 原始SQL字符串
     * @return 替换占位符后的SQL字符串
     */
    private String replacePlaceholder(String sql) {
        return PropertyParser.parse(sql, configuration.getVariables());
    }

} // ProviderSqlSource类结束 - 该类实现了通过调用SQL提供者方法动态生成SQL语句的功能，
// 支持多种参数传递方式，并负责将生成的SQL字符串解析为可执行的SqlSource对象