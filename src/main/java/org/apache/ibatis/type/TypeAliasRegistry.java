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
package org.apache.ibatis.type;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.io.Resources;

/**
 * @author Clinton Begin
 */
public class TypeAliasRegistry {

    private final Map<String, Class<?>> TYPE_ALIASES = new HashMap<String, Class<?>>();

    public TypeAliasRegistry() {
        registerAlias("string", String.class);

        registerAlias("byte", Byte.class);
        registerAlias("long", Long.class);
        registerAlias("short", Short.class);
        registerAlias("int", Integer.class);
        registerAlias("integer", Integer.class);
        registerAlias("double", Double.class);
        registerAlias("float", Float.class);
        registerAlias("boolean", Boolean.class);

        registerAlias("byte[]", Byte[].class);
        registerAlias("long[]", Long[].class);
        registerAlias("short[]", Short[].class);
        registerAlias("int[]", Integer[].class);
        registerAlias("integer[]", Integer[].class);
        registerAlias("double[]", Double[].class);
        registerAlias("float[]", Float[].class);
        registerAlias("boolean[]", Boolean[].class);

        registerAlias("_byte", byte.class);
        registerAlias("_long", long.class);
        registerAlias("_short", short.class);
        registerAlias("_int", int.class);
        registerAlias("_integer", int.class);
        registerAlias("_double", double.class);
        registerAlias("_float", float.class);
        registerAlias("_boolean", boolean.class);

        registerAlias("_byte[]", byte[].class);
        registerAlias("_long[]", long[].class);
        registerAlias("_short[]", short[].class);
        registerAlias("_int[]", int[].class);
        registerAlias("_integer[]", int[].class);
        registerAlias("_double[]", double[].class);
        registerAlias("_float[]", float[].class);
        registerAlias("_boolean[]", boolean[].class);

        registerAlias("date", Date.class);
        registerAlias("decimal", BigDecimal.class);
        registerAlias("bigdecimal", BigDecimal.class);
        registerAlias("biginteger", BigInteger.class);
        registerAlias("object", Object.class);

        registerAlias("date[]", Date[].class);
        registerAlias("decimal[]", BigDecimal[].class);
        registerAlias("bigdecimal[]", BigDecimal[].class);
        registerAlias("biginteger[]", BigInteger[].class);
        registerAlias("object[]", Object[].class);

        registerAlias("map", Map.class);
        registerAlias("hashmap", HashMap.class);
        registerAlias("list", List.class);
        registerAlias("arraylist", ArrayList.class);
        registerAlias("collection", Collection.class);
        registerAlias("iterator", Iterator.class);

        registerAlias("ResultSet", ResultSet.class);
    }

    /**
     * 解析类型别名为对应的类
     * 首先在已注册的别名映射表中查找，如果找不到则尝试通过类名加载类
     * 别名不区分大小写，使用英文环境进行大小写转换
     *
     * @param <T>    返回的类类型
     * @param string 类型别名或类的全限定名
     * @return 别名对应的类，如果输入为null则返回null
     * @throws TypeException 当别名无法解析或类无法加载时抛出异常
     */
    @SuppressWarnings("unchecked")
    // 如果类型无法分配，也会抛出类转换异常
    public <T> Class<T> resolveAlias(String string) {
        try {
            // 检查输入字符串是否为null
            if (string == null) {
                // 如果为null，直接返回null
                return null;
            }
            // issue #748
            // 将字符串转换为小写形式，确保别名不区分大小写
            String key = string.toLowerCase(Locale.ENGLISH);
            // 声明Class<T>类型的变量value，用于存储解析后的类
            Class<T> value;
            // 检查TYPE_ALIASES映射表中是否包含该key
            if (TYPE_ALIASES.containsKey(key)) {
                // 如果包含，从映射表中获取对应的类并强制转换为Class<T>类型
                value = (Class<T>) TYPE_ALIASES.get(key);
            } else {
                // 如果不包含，尝试通过Resources.classForName方法加载类
                value = (Class<T>) Resources.classForName(string);
            }
            // 返回解析后的类
            return value;
        } catch (ClassNotFoundException e) {
            // 如果类加载失败，抛出TypeException异常，并包含原始异常信息
            throw new TypeException("Could not resolve type alias '" + string + "'.  Cause: " + e, e);
        }
    }


    /**
     * 注册指定包名下的所有类的类型别名
     * 使用Object.class作为超类类型，即注册包下的所有非接口、非匿名、非内部类
     *
     * @param packageName 要扫描的包名
     */
    public void registerAliases(String packageName) {
        // 调用重载方法，以Object.class作为超类类型
        registerAliases(packageName, Object.class);
    }

    /**
     * 注册指定包名下的特定超类或接口的实现类的类型别名
     * 扫描指定包，找到所有继承或实现了指定超类的类，并注册它们的类型别名
     *
     * @param packageName 要扫描的包名
     * @param superType   超类或接口类型，用于过滤符合条件的类
     */
    public void registerAliases(String packageName, Class<?> superType) {
        // 创建ResolverUtil实例，用于类路径扫描和解析
        ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<Class<?>>();
        // 使用IsA测试器查找指定包下继承或实现了superType的所有类
        resolverUtil.find(new ResolverUtil.IsA(superType), packageName);
        // 获取查找到的符合条件的类集合
        Set<Class<? extends Class<?>>> typeSet = resolverUtil.getClasses();
        // 遍历查找到的所有类
        for (Class<?> type : typeSet) {
            // Ignore inner classes and interfaces (including package-info.java)
            // Skip also inner classes. See issue #6
            // 检查类是否为匿名类、接口或成员类，这些类型将被忽略
            if (!type.isAnonymousClass() && !type.isInterface() && !type.isMemberClass()) {
                // 注册符合条件的类的类型别名
                registerAlias(type);
            }
        }
    }

    /**
     * 注册类的类型别名
     * 使用类的简单名称作为默认别名，如果类上有@Alias注解，则使用注解指定的值作为别名
     *
     * @param type 要注册类型别名的类
     */
    public void registerAlias(Class<?> type) {
        // 获取类的简单名称（不包含包名）作为默认别名
        String alias = type.getSimpleName();
        // 获取类上的@Alias注解
        Alias aliasAnnotation = type.getAnnotation(Alias.class);
        // 检查是否存在@Alias注解
        if (aliasAnnotation != null) {
            // 如果存在@Alias注解，使用注解中指定的值作为别名
            alias = aliasAnnotation.value();
        }
        // 调用重载方法注册别名和类的映射关系
        registerAlias(alias, type);
    }


    /**
     * 注册指定别名和类的映射关系
     * 将别名转换为小写后存储，确保别名不区分大小写
     * 如果别名已映射到不同的类，则抛出异常防止冲突
     *
     * @param alias 要注册的别名
     * @param value 别名对应的类
     * @throws TypeException 当别名为null或别名已映射到不同的类时抛出异常
     */
    public void registerAlias(String alias, Class<?> value) {
        // 检查别名是否为null
        if (alias == null) {
            // 如果别名为null，抛出TypeException异常
            throw new TypeException("The parameter alias cannot be null");
        }
        // issue #748
        // 将别名转换为小写形式，确保别名不区分大小写
        String key = alias.toLowerCase(Locale.ENGLISH);
        // 检查别名是否已存在且映射到不同的类
        if (TYPE_ALIASES.containsKey(key) && TYPE_ALIASES.get(key) != null && !TYPE_ALIASES.get(key).equals(value)) {
            // 如果别名已映射到不同的类，抛出TypeException异常
            throw new TypeException("The alias '" + alias + "' is already mapped to the value '" + TYPE_ALIASES.get(key).getName() + "'.");
        }
        // 将别名和类的映射关系存入TYPE_ALIASES映射表中
        TYPE_ALIASES.put(key, value);
    }

    /**
     * 注册指定别名和类名的映射关系
     * 通过类名字符串加载对应的类对象，然后调用registerAlias(String, Class<?>)方法完成注册
     *
     * @param alias 要注册的别名
     * @param value 别名对应的类的全限定名字符串
     * @throws TypeException 当类无法加载时抛出异常
     */
    public void registerAlias(String alias, String value) {
        try {
            // 通过Resources.classForName方法根据类名加载对应的Class对象
            registerAlias(alias, Resources.classForName(value));
        } catch (ClassNotFoundException e) {
            // 如果类加载失败，抛出TypeException异常，并包含原始异常信息
            throw new TypeException("Error registering type alias " + alias + " for " + value + ". Cause: " + e, e);
        }
    }

    /**
     * @since 3.2.2
     */
    public Map<String, Class<?>> getTypeAliases() {
        return Collections.unmodifiableMap(TYPE_ALIASES);
    }

}
