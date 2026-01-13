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

package org.apache.ibatis.reflection;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

public class ParamNameResolver {

    private static final String GENERIC_NAME_PREFIX = "param";

    /**
     * <p>
     * The key is the index and the value is the name of the parameter.<br />
     * The name is obtained from {@link Param} if specified. When {@link Param} is not specified,
     * the parameter index is used. Note that this index could be different from the actual index
     * when the method has special parameters (i.e. {@link RowBounds} or {@link ResultHandler}).
     * </p>
     * <ul>
     * <li>aMethod(@Param("M") int a, @Param("N") int b) -&gt; {{0, "M"}, {1, "N"}}</li>
     * <li>aMethod(int a, int b) -&gt; {{0, "0"}, {1, "1"}}</li>
     * <li>aMethod(int a, RowBounds rb, int b) -&gt; {{0, "0"}, {2, "1"}}</li>
     * </ul>
     *
     *
     */
    private final SortedMap<Integer, String> names;

    private boolean hasParamAnnotation;

    /**
     * 构造函数，解析方法的参数信息并初始化参数名称映射。
     * <p>
     * 该构造函数负责分析方法的参数类型和注解，为每个参数确定名称，并建立参数索引与名称的映射关系。
     * 参数名称的确定遵循以下优先级：
     * 1. 如果参数使用了@Param注解，则使用注解指定的名称
     * 2. 如果配置启用了useActualParamName且编译时保留了参数名，则使用实际参数名
     * 3. 否则使用参数索引作为名称（从0开始）
     * <p>
     * 特殊参数（RowBounds和ResultHandler）会被跳过，不参与参数名称映射。
     *
     * @param config MyBatis配置对象，用于获取是否使用实际参数名的设置
     * @param method 需要解析参数的方法对象
     */
    public ParamNameResolver(Configuration config, Method method) {
        // 获取方法的参数类型数组
        final Class<?>[] paramTypes = method.getParameterTypes();
        // 获取方法的参数注解二维数组
        final Annotation[][] paramAnnotations = method.getParameterAnnotations();
        // 创建有序映射，用于存储参数索引与名称的对应关系
        final SortedMap<Integer, String> map = new TreeMap<Integer, String>();
        // 参数总数
        int paramCount = paramAnnotations.length;

        // 遍历所有参数，确定每个参数的名称
        for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
            // 跳过特殊参数（RowBounds和ResultHandler）
            if (isSpecialParameter(paramTypes[paramIndex])) {
                continue;
            }

            String name = null;
            // 检查参数是否有@Param注解
            for (Annotation annotation : paramAnnotations[paramIndex]) {
                if (annotation instanceof Param) {
                    hasParamAnnotation = true;
                    name = ((Param) annotation).value();
                    break;
                }
            }

            if (name == null) {
                // 没有@Param注解，尝试使用实际参数名
                if (config.isUseActualParamName()) {
                    name = getActualParamName(method, paramIndex);
                }
                // 如果无法获取实际参数名，使用参数索引作为名称
                if (name == null) {
                    // 使用参数索引作为名称（"0", "1", ...）
                    // 解决GitHub issue #71
                    name = String.valueOf(map.size());
                }
            }

            // 将参数索引和名称存入映射
            map.put(paramIndex, name);
        }

        // 创建不可修改的有序映射
        names = Collections.unmodifiableSortedMap(map);
    }

    private String getActualParamName(Method method, int paramIndex) {
        if (Jdk.parameterExists) {
            return ParamNameUtil.getParamNames(method).get(paramIndex);
        }
        return null;
    }

    private static boolean isSpecialParameter(Class<?> clazz) {
        return RowBounds.class.isAssignableFrom(clazz) || ResultHandler.class.isAssignableFrom(clazz);
    }

    /**
     * Returns parameter names referenced by SQL providers.
     */
    public String[] getNames() {
        return names.values().toArray(new String[0]);
    }

    /**
     * 将方法参数数组转换为命名参数Map，供SQL语句使用。
     *
     * <p>该方法根据参数名称映射规则，将方法参数转换为MyBatis可以使用的参数形式。
     * 转换规则如下：</p>
     * <ul>
     * <li>当没有参数时，返回null</li>
     * <li>当只有一个普通参数且没有使用@Param注解时，直接返回该参数值</li>
     * <li>当有多个参数或使用了@Param注解时，返回一个包含所有参数的Map</li>
     * </ul>
     *
     * <p>对于多参数情况，除了使用参数名称外，还会添加通用参数名称（param1, param2, ...），
     * 这样SQL语句可以通过两种方式引用参数：使用自定义名称或通用名称。</p>
     *
     * <p>示例：</p>
     * <ul>
     * <li>方法：findById(int id) -&gt; 返回：id的值</li>
     * <li>方法：findByNameAndAge(@Param("name") String name, @Param("age") int age)
     *   -&gt; 返回：{"name": name值, "age": age值, "param1": name值, "param2": age值}</li>
     * <li>方法：findByNameAndAge(String name, int age)
     *   -&gt; 返回：{"0": name值, "1": age值, "param1": name值, "param2": age值}</li>
     * </ul>
     *
     * @param args 方法参数数组，包含所有参数的实际值
     * @return 根据参数数量和注解情况返回null、单个参数值或参数Map
     */
    public Object getNamedParams(Object[] args) {
        // 获取参数数量
        final int paramCount = names.size();

        // 处理无参数情况
        if (args == null || paramCount == 0) {
            return null;
        }
        // 处理只有一个普通参数且没有使用@Param注解的情况
        else if (!hasParamAnnotation && paramCount == 1) {
            // 直接返回该参数值，不需要包装成Map
            return args[names.firstKey()];
        }
        // 处理多参数或使用了@Param注解的情况
        else {
            // 创建参数Map，使用自定义的ParamMap实现
            final Map<String, Object> param = new ParamMap<Object>();
            int i = 0;

            // 遍历所有参数名称映射
            for (Map.Entry<Integer, String> entry : names.entrySet()) {
                // 将参数值放入Map，键为参数名称
                param.put(entry.getValue(), args[entry.getKey()]);

                // 添加通用参数名称（param1, param2, ...）
                final String genericParamName = GENERIC_NAME_PREFIX + String.valueOf(i + 1);

                // 确保不会覆盖使用@Param注解指定的参数名称
                if (!names.containsValue(genericParamName)) {
                    param.put(genericParamName, args[entry.getKey()]);
                }
                i++;
            }
            return param;
        }
    }
}
