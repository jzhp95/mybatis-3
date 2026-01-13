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
package org.apache.ibatis.binding;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

import org.apache.ibatis.lang.UsesJava7;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.SqlSession;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class MapperProxy<T> implements InvocationHandler, Serializable {

    private static final long serialVersionUID = -6424540398559729838L;
    private final SqlSession sqlSession;
    private final Class<T> mapperInterface;
    private final Map<Method, MapperMethod> methodCache;

    public MapperProxy(SqlSession sqlSession, Class<T> mapperInterface, Map<Method, MapperMethod> methodCache) {
        this.sqlSession = sqlSession;
        this.mapperInterface = mapperInterface;
        this.methodCache = methodCache;
    }

    /**
     * 代理方法调用处理器，实现Mapper接口方法的动态调用。
     * <p>
     * 该方法是MapperProxy的核心，负责拦截对Mapper接口方法的调用，并将其转换为对MyBatis执行器的调用。
     * <p>
     * 处理流程如下：
     * 1. 如果调用的是Object类的方法（如toString、hashCode等），直接调用MapperProxy本身的对应方法
     * 2. 如果调用的是Java 8接口默认方法，使用反射机制调用默认方法实现
     * 3. 对于其他Mapper接口方法，获取缓存的MapperMethod对象并执行SQL操作
     *
     * @param proxy  代理对象实例
     * @param method 被调用的方法对象
     * @param args   方法调用参数
     * @return 方法执行结果，可能是查询结果集、更新影响的行数或其他操作结果
     * @throws Throwable 如果方法执行过程中发生任何异常
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            // 检查是否为Object类的方法，如果是则直接调用本对象的对应方法
            if (Object.class.equals(method.getDeclaringClass())) {
                return method.invoke(this, args);
            }
            // 检查是否为Java 8接口默认方法，如果是则调用默认方法实现
            else if (isDefaultMethod(method)) {
                return invokeDefaultMethod(proxy, method, args);
            }
        } catch (Throwable t) {
            // 解包并抛出实际的异常，去除不必要的包装异常
            throw ExceptionUtil.unwrapThrowable(t);
        }
        // 获取或创建缓存的MapperMethod对象，并执行对应的SQL操作
        // 将 method 转换为 MapperMethod 对象
        final MapperMethod mapperMethod = cachedMapperMethod(method);
        // 调用 MapperMethod 的 execute 方法执行 SQL 操作
        return mapperMethod.execute(sqlSession, args);
    }

    /**
     * 获取或创建缓存的MapperMethod对象，用于执行Mapper接口方法对应的SQL操作。
     * <p>
     * 该方法会检查方法缓存中是否已存在该方法的MapperMethod对象，如果不存在则创建一个新的MapperMethod对象并缓存起来。
     *
     * @param method 被调用的方法对象
     * @return 缓存的MapperMethod对象，用于执行SQL操作
     */
    private MapperMethod cachedMapperMethod(Method method) {
        MapperMethod mapperMethod = methodCache.get(method);
        if (mapperMethod == null) {
            // 解析原 method，创建新的 MapperMethod 对象并缓存
            // 方便后续进行调用
            mapperMethod = new MapperMethod(mapperInterface, method, sqlSession.getConfiguration());
            methodCache.put(method, mapperMethod);
        }
        return mapperMethod;
    }

    @UsesJava7
    private Object invokeDefaultMethod(Object proxy, Method method, Object[] args)
            throws Throwable {
        final Constructor<MethodHandles.Lookup> constructor = MethodHandles.Lookup.class
                .getDeclaredConstructor(Class.class, int.class);
        if (!constructor.isAccessible()) {
            constructor.setAccessible(true);
        }
        final Class<?> declaringClass = method.getDeclaringClass();
        return constructor
                .newInstance(declaringClass,
                        MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PROTECTED
                                | MethodHandles.Lookup.PACKAGE | MethodHandles.Lookup.PUBLIC)
                .unreflectSpecial(method, declaringClass).bindTo(proxy).invokeWithArguments(args);
    }

    /**
     * Backport of java.lang.reflect.Method#isDefault()
     */
    private boolean isDefaultMethod(Method method) {
        return (method.getModifiers()
                & (Modifier.ABSTRACT | Modifier.PUBLIC | Modifier.STATIC)) == Modifier.PUBLIC
                && method.getDeclaringClass().isInterface();
    }
}
