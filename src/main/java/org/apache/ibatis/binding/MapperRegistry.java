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
package org.apache.ibatis.binding;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 */
public class MapperRegistry {

    private final Configuration config;
    private final Map<Class<?>, MapperProxyFactory<?>> knownMappers = new HashMap<Class<?>, MapperProxyFactory<?>>();

    public MapperRegistry(Configuration config) {
        this.config = config;
    }

    @SuppressWarnings("unchecked")
    public <T> T getMapper(Class<T> type, SqlSession sqlSession) {
        // 从 knownMappers 中根据 type 找到对应的 MapperProxyFactory
        final MapperProxyFactory<T> mapperProxyFactory = (MapperProxyFactory<T>) knownMappers.get(type);
        if (mapperProxyFactory == null) {
            throw new BindingException("Type " + type + " is not known to the MapperRegistry.");
        }
        try {
            // 从 MapperProxyFactory 中创建一个 代理对象（MapperProxy JDK动态代理）
            return mapperProxyFactory.newInstance(sqlSession);
        } catch (Exception e) {
            throw new BindingException("Error getting mapper instance. Cause: " + e, e);
        }
    }

    public <T> boolean hasMapper(Class<T> type) {
        return knownMappers.containsKey(type);
    }

    /**
     * 注册映射器接口到映射器注册表中
     * <p>
     * 该方法用于将指定的映射器接口注册到MyBatis的映射器注册表中，以便后续可以通过
     * SqlSession获取该接口的代理实现。注册过程中会创建对应的MapperProxyFactory，
     * 并解析接口上的注解信息，构建相应的MappedStatement对象。
     * </p>
     * <p>
     * 执行流程：
     * <ol>
     *   <li>检查传入的类型是否为接口，非接口类型将被忽略</li>
     *   <li>检查该接口是否已经注册，避免重复注册</li>
     *   <li>为该接口创建MapperProxyFactory并注册到knownMappers映射中</li>
     *   <li>创建MapperAnnotationBuilder解析器，解析接口上的注解</li>
     *   <li>如果解析过程中出现异常，从knownMappers中移除该接口</li>
     * </ol>
     * </p>
     * <p>
     * 注意事项：
     * <ul>
     *   <li>只有接口类型才能被注册，非接口类型会被忽略</li>
     *   <li>同一个接口不能重复注册，否则会抛出BindingException</li>
     *   <li>注册过程中会解析接口上的注解，如@Select、@Insert等</li>
     *   <li>该方法必须在解析器运行前将类型添加到knownMappers中，以避免自动绑定尝试</li>
     * </ul>
     * </p>
     *
     * @param <T>  映射器接口的泛型类型
     * @param type 要注册的映射器接口的Class对象
     * @throws BindingException 当尝试注册已经存在的映射器接口时抛出
     * @see MapperProxyFactory 为映射器接口创建代理对象的工厂类
     * @see MapperAnnotationBuilder 解析映射器接口注解的构建器
     * @see #hasMapper(Class) 检查映射器是否已注册的方法
     * @see #getMapper(Class, SqlSession) 获取映射器代理实例的方法
     */
    public <T> void addMapper(Class<T> type) {
        // 得是一个接口
        if (type.isInterface()) {
            // 已经注册了就抛出异常 重复注册
            if (hasMapper(type)) {
                throw new BindingException("Type " + type + " is already known to the MapperRegistry.");
            }

            // 标记加载是否完成，用于异常处理
            boolean loadCompleted = false;
            try {
                // 最终结果就是  每个 mapper 文件注册一个 MapperProxyFactory
                // 每个 mapper 文件的 sql 语句解析完注册一个 MappedStatement

                // 把当前的 class 封装为一个 MapperProxyFactory 并注册到 knownMappers 中
                knownMappers.put(type, new MapperProxyFactory<T>(type));

                // It's important that the type is added before the parser is run
                // otherwise the binding may automatically be attempted by the
                // mapper parser. If the type is already known, it won't try.
                // 创建注解解析器，解析接口上的注解信息
                MapperAnnotationBuilder parser = new MapperAnnotationBuilder(config, type);


                // 解析接口上的注解，构建MappedStatement
                parser.parse();

                // 标记加载完成
                loadCompleted = true;
            } finally {
                // 如果加载未完成，从knownMappers中移除该接口
                if (!loadCompleted) {
                    knownMappers.remove(type);
                }
            }
        }
    }


    /**
     * @since 3.2.2
     */
    public Collection<Class<?>> getMappers() {
        return Collections.unmodifiableCollection(knownMappers.keySet());
    }

    /**
     * 批量注册指定包下的所有映射器接口
     * <p>
     * 该方法通过反射扫描指定包下的所有类，筛选出继承自指定父类的接口，
     * 并将它们注册到映射器注册表中。这是MyBatis中批量注册映射器的主要方式，
     * 通常在配置解析过程中被调用，用于自动发现和注册映射器接口。
     * </p>
     * <p>
     * 执行流程：
     * <ol>
     *   <li>创建ResolverUtil实例，用于类路径扫描和类型匹配</li>
     *   <li>使用IsA测试器筛选出继承自superType的所有类</li>
     *   <li>获取扫描结果中的所有类</li>
     *   <li>遍历这些类，逐个调用addMapper方法进行注册</li>
     * </ol>
     * </p>
     * <p>
     * 注意事项：
     * <ul>
     *   <li>只有接口才会被成功注册，非接口类会被addMapper方法忽略</li>
     *   <li>如果接口已经注册过，会抛出BindingException异常</li>
     *   <li>注册过程中会解析接口上的注解，构建相应的MappedStatement</li>
     * </ul>
     * </p>
     *
     * @param packageName 要扫描的包名，如"com.example.mapper"
     * @param superType   父类型，只有继承或实现此类型的类才会被注册，通常使用Object.class表示所有类
     * @see #addMapper(Class) 单个映射器注册方法
     * @see ResolverUtil 用于类路径扫描的工具类
     * @see ResolverUtil.IsA 用于测试类是否继承自指定类型的测试器
     * @since 3.2.2
     */
    public void addMappers(String packageName, Class<?> superType) {
        // 创建一个反射工具类
        ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<Class<?>>();
        // 扫描包下的所有.class文件，筛选出符合条件的，放入到 matches 中
        resolverUtil.find(new ResolverUtil.IsA(superType), packageName);
        Set<Class<? extends Class<?>>> mapperSet = resolverUtil.getClasses();

        // 挨个 Class 进行注册
        for (Class<?> mapperClass : mapperSet) {
            addMapper(mapperClass);
        }
    }


    /**
     * @since 3.2.2
     */
    public void addMappers(String packageName) {
        addMappers(packageName, Object.class);
    }

}
