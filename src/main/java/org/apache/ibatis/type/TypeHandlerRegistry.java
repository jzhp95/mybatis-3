/**
 * Copyright 2009-2018 the original author or authors.
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

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.chrono.JapaneseDate;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.reflection.Jdk;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public final class TypeHandlerRegistry {

    private final Map<JdbcType, TypeHandler<?>> JDBC_TYPE_HANDLER_MAP = new EnumMap<JdbcType, TypeHandler<?>>(JdbcType.class);
    private final Map<Type, Map<JdbcType, TypeHandler<?>>> TYPE_HANDLER_MAP = new ConcurrentHashMap<Type, Map<JdbcType, TypeHandler<?>>>();
    private final TypeHandler<Object> UNKNOWN_TYPE_HANDLER = new UnknownTypeHandler(this);
    private final Map<Class<?>, TypeHandler<?>> ALL_TYPE_HANDLERS_MAP = new HashMap<Class<?>, TypeHandler<?>>();

    private static final Map<JdbcType, TypeHandler<?>> NULL_TYPE_HANDLER_MAP = Collections.emptyMap();

    private Class<? extends TypeHandler> defaultEnumTypeHandler = EnumTypeHandler.class;

    public TypeHandlerRegistry() {
        register(Boolean.class, new BooleanTypeHandler());
        register(boolean.class, new BooleanTypeHandler());
        register(JdbcType.BOOLEAN, new BooleanTypeHandler());
        register(JdbcType.BIT, new BooleanTypeHandler());

        register(Byte.class, new ByteTypeHandler());
        register(byte.class, new ByteTypeHandler());
        register(JdbcType.TINYINT, new ByteTypeHandler());

        register(Short.class, new ShortTypeHandler());
        register(short.class, new ShortTypeHandler());
        register(JdbcType.SMALLINT, new ShortTypeHandler());

        register(Integer.class, new IntegerTypeHandler());
        register(int.class, new IntegerTypeHandler());
        register(JdbcType.INTEGER, new IntegerTypeHandler());

        register(Long.class, new LongTypeHandler());
        register(long.class, new LongTypeHandler());

        register(Float.class, new FloatTypeHandler());
        register(float.class, new FloatTypeHandler());
        register(JdbcType.FLOAT, new FloatTypeHandler());

        register(Double.class, new DoubleTypeHandler());
        register(double.class, new DoubleTypeHandler());
        register(JdbcType.DOUBLE, new DoubleTypeHandler());

        register(Reader.class, new ClobReaderTypeHandler());
        register(String.class, new StringTypeHandler());
        register(String.class, JdbcType.CHAR, new StringTypeHandler());
        register(String.class, JdbcType.CLOB, new ClobTypeHandler());
        register(String.class, JdbcType.VARCHAR, new StringTypeHandler());
        register(String.class, JdbcType.LONGVARCHAR, new ClobTypeHandler());
        register(String.class, JdbcType.NVARCHAR, new NStringTypeHandler());
        register(String.class, JdbcType.NCHAR, new NStringTypeHandler());
        register(String.class, JdbcType.NCLOB, new NClobTypeHandler());
        register(JdbcType.CHAR, new StringTypeHandler());
        register(JdbcType.VARCHAR, new StringTypeHandler());
        register(JdbcType.CLOB, new ClobTypeHandler());
        register(JdbcType.LONGVARCHAR, new ClobTypeHandler());
        register(JdbcType.NVARCHAR, new NStringTypeHandler());
        register(JdbcType.NCHAR, new NStringTypeHandler());
        register(JdbcType.NCLOB, new NClobTypeHandler());

        register(Object.class, JdbcType.ARRAY, new ArrayTypeHandler());
        register(JdbcType.ARRAY, new ArrayTypeHandler());

        register(BigInteger.class, new BigIntegerTypeHandler());
        register(JdbcType.BIGINT, new LongTypeHandler());

        register(BigDecimal.class, new BigDecimalTypeHandler());
        register(JdbcType.REAL, new BigDecimalTypeHandler());
        register(JdbcType.DECIMAL, new BigDecimalTypeHandler());
        register(JdbcType.NUMERIC, new BigDecimalTypeHandler());

        register(InputStream.class, new BlobInputStreamTypeHandler());
        register(Byte[].class, new ByteObjectArrayTypeHandler());
        register(Byte[].class, JdbcType.BLOB, new BlobByteObjectArrayTypeHandler());
        register(Byte[].class, JdbcType.LONGVARBINARY, new BlobByteObjectArrayTypeHandler());
        register(byte[].class, new ByteArrayTypeHandler());
        register(byte[].class, JdbcType.BLOB, new BlobTypeHandler());
        register(byte[].class, JdbcType.LONGVARBINARY, new BlobTypeHandler());
        register(JdbcType.LONGVARBINARY, new BlobTypeHandler());
        register(JdbcType.BLOB, new BlobTypeHandler());

        register(Object.class, UNKNOWN_TYPE_HANDLER);
        register(Object.class, JdbcType.OTHER, UNKNOWN_TYPE_HANDLER);
        register(JdbcType.OTHER, UNKNOWN_TYPE_HANDLER);

        register(Date.class, new DateTypeHandler());
        register(Date.class, JdbcType.DATE, new DateOnlyTypeHandler());
        register(Date.class, JdbcType.TIME, new TimeOnlyTypeHandler());
        register(JdbcType.TIMESTAMP, new DateTypeHandler());
        register(JdbcType.DATE, new DateOnlyTypeHandler());
        register(JdbcType.TIME, new TimeOnlyTypeHandler());

        register(java.sql.Date.class, new SqlDateTypeHandler());
        register(java.sql.Time.class, new SqlTimeTypeHandler());
        register(java.sql.Timestamp.class, new SqlTimestampTypeHandler());

        // mybatis-typehandlers-jsr310
        if (Jdk.dateAndTimeApiExists) {
            this.register(Instant.class, InstantTypeHandler.class);
            this.register(LocalDateTime.class, LocalDateTimeTypeHandler.class);
            this.register(LocalDate.class, LocalDateTypeHandler.class);
            this.register(LocalTime.class, LocalTimeTypeHandler.class);
            this.register(OffsetDateTime.class, OffsetDateTimeTypeHandler.class);
            this.register(OffsetTime.class, OffsetTimeTypeHandler.class);
            this.register(ZonedDateTime.class, ZonedDateTimeTypeHandler.class);
            this.register(Month.class, MonthTypeHandler.class);
            this.register(Year.class, YearTypeHandler.class);
            this.register(YearMonth.class, YearMonthTypeHandler.class);
            this.register(JapaneseDate.class, JapaneseDateTypeHandler.class);
        }

        // issue #273
        register(Character.class, new CharacterTypeHandler());
        register(char.class, new CharacterTypeHandler());
    }

    /**
     * Set a default {@link TypeHandler} class for {@link Enum}.
     * A default {@link TypeHandler} is {@link org.apache.ibatis.type.EnumTypeHandler}.
     *
     * @param typeHandler a type handler class for {@link Enum}
     * @since 3.4.5
     */
    public void setDefaultEnumTypeHandler(Class<? extends TypeHandler> typeHandler) {
        this.defaultEnumTypeHandler = typeHandler;
    }

    public boolean hasTypeHandler(Class<?> javaType) {
        return hasTypeHandler(javaType, null);
    }

    public boolean hasTypeHandler(TypeReference<?> javaTypeReference) {
        return hasTypeHandler(javaTypeReference, null);
    }

    public boolean hasTypeHandler(Class<?> javaType, JdbcType jdbcType) {
        return javaType != null && getTypeHandler((Type) javaType, jdbcType) != null;
    }

    public boolean hasTypeHandler(TypeReference<?> javaTypeReference, JdbcType jdbcType) {
        return javaTypeReference != null && getTypeHandler(javaTypeReference, jdbcType) != null;
    }

    public TypeHandler<?> getMappingTypeHandler(Class<? extends TypeHandler<?>> handlerType) {
        return ALL_TYPE_HANDLERS_MAP.get(handlerType);
    }

    public <T> TypeHandler<T> getTypeHandler(Class<T> type) {
        return getTypeHandler((Type) type, null);
    }

    public <T> TypeHandler<T> getTypeHandler(TypeReference<T> javaTypeReference) {
        return getTypeHandler(javaTypeReference, null);
    }

    public TypeHandler<?> getTypeHandler(JdbcType jdbcType) {
        return JDBC_TYPE_HANDLER_MAP.get(jdbcType);
    }

    public <T> TypeHandler<T> getTypeHandler(Class<T> type, JdbcType jdbcType) {
        return getTypeHandler((Type) type, jdbcType);
    }

    public <T> TypeHandler<T> getTypeHandler(TypeReference<T> javaTypeReference, JdbcType jdbcType) {
        return getTypeHandler(javaTypeReference.getRawType(), jdbcType);
    }

    @SuppressWarnings("unchecked")
    private <T> TypeHandler<T> getTypeHandler(Type type, JdbcType jdbcType) {
        if (ParamMap.class.equals(type)) {
            return null;
        }
        Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = getJdbcHandlerMap(type);
        TypeHandler<?> handler = null;
        if (jdbcHandlerMap != null) {
            handler = jdbcHandlerMap.get(jdbcType);
            if (handler == null) {
                handler = jdbcHandlerMap.get(null);
            }
            if (handler == null) {
                // #591
                handler = pickSoleHandler(jdbcHandlerMap);
            }
        }
        // type drives generics here
        return (TypeHandler<T>) handler;
    }

    private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMap(Type type) {
        Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = TYPE_HANDLER_MAP.get(type);
        if (NULL_TYPE_HANDLER_MAP.equals(jdbcHandlerMap)) {
            return null;
        }
        if (jdbcHandlerMap == null && type instanceof Class) {
            Class<?> clazz = (Class<?>) type;
            if (clazz.isEnum()) {
                jdbcHandlerMap = getJdbcHandlerMapForEnumInterfaces(clazz, clazz);
                if (jdbcHandlerMap == null) {
                    register(clazz, getInstance(clazz, defaultEnumTypeHandler));
                    return TYPE_HANDLER_MAP.get(clazz);
                }
            } else {
                jdbcHandlerMap = getJdbcHandlerMapForSuperclass(clazz);
            }
        }
        TYPE_HANDLER_MAP.put(type, jdbcHandlerMap == null ? NULL_TYPE_HANDLER_MAP : jdbcHandlerMap);
        return jdbcHandlerMap;
    }

    private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMapForEnumInterfaces(Class<?> clazz, Class<?> enumClazz) {
        for (Class<?> iface : clazz.getInterfaces()) {
            Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = TYPE_HANDLER_MAP.get(iface);
            if (jdbcHandlerMap == null) {
                jdbcHandlerMap = getJdbcHandlerMapForEnumInterfaces(iface, enumClazz);
            }
            if (jdbcHandlerMap != null) {
                // Found a type handler regsiterd to a super interface
                HashMap<JdbcType, TypeHandler<?>> newMap = new HashMap<JdbcType, TypeHandler<?>>();
                for (Entry<JdbcType, TypeHandler<?>> entry : jdbcHandlerMap.entrySet()) {
                    // Create a type handler instance with enum type as a constructor arg
                    newMap.put(entry.getKey(), getInstance(enumClazz, entry.getValue().getClass()));
                }
                return newMap;
            }
        }
        return null;
    }

    private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMapForSuperclass(Class<?> clazz) {
        Class<?> superclass = clazz.getSuperclass();
        if (superclass == null || Object.class.equals(superclass)) {
            return null;
        }
        Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = TYPE_HANDLER_MAP.get(superclass);
        if (jdbcHandlerMap != null) {
            return jdbcHandlerMap;
        } else {
            return getJdbcHandlerMapForSuperclass(superclass);
        }
    }

    private TypeHandler<?> pickSoleHandler(Map<JdbcType, TypeHandler<?>> jdbcHandlerMap) {
        TypeHandler<?> soleHandler = null;
        for (TypeHandler<?> handler : jdbcHandlerMap.values()) {
            if (soleHandler == null) {
                soleHandler = handler;
            } else if (!handler.getClass().equals(soleHandler.getClass())) {
                // More than one type handlers registered.
                return null;
            }
        }
        return soleHandler;
    }

    public TypeHandler<Object> getUnknownTypeHandler() {
        return UNKNOWN_TYPE_HANDLER;
    }

    public void register(JdbcType jdbcType, TypeHandler<?> handler) {
        JDBC_TYPE_HANDLER_MAP.put(jdbcType, handler);
    }

    //
    // REGISTER INSTANCE
    //

    // Only handler

    @SuppressWarnings("unchecked")
    public <T> void register(TypeHandler<T> typeHandler) {
        boolean mappedTypeFound = false;
        MappedTypes mappedTypes = typeHandler.getClass().getAnnotation(MappedTypes.class);
        if (mappedTypes != null) {
            for (Class<?> handledType : mappedTypes.value()) {
                register(handledType, typeHandler);
                mappedTypeFound = true;
            }
        }
        // @since 3.1.0 - try to auto-discover the mapped type
        if (!mappedTypeFound && typeHandler instanceof TypeReference) {
            try {
                TypeReference<T> typeReference = (TypeReference<T>) typeHandler;
                register(typeReference.getRawType(), typeHandler);
                mappedTypeFound = true;
            } catch (Throwable t) {
                // maybe users define the TypeReference with a different type and are not assignable, so just ignore it
            }
        }
        if (!mappedTypeFound) {
            register((Class<T>) null, typeHandler);
        }
    }

    // java type + handler

    public <T> void register(Class<T> javaType, TypeHandler<? extends T> typeHandler) {
        register((Type) javaType, typeHandler);
    }

    /**
     * 注册TypeHandler到指定的Java类型
     * <p>
     * 该方法是一个内部方法，用于将TypeHandler注册到指定的Java类型。它会检查TypeHandler类上
     * 是否有@MappedJdbcTypes注解，如果有，则将该TypeHandler注册到注解中指定的所有JDBC类型；
     * 如果没有，则将该TypeHandler注册为默认处理器（JDBC类型为null）。
     * <p>
     * 该方法是TypeHandler注册体系的核心部分，负责处理TypeHandler与JDBC类型的映射关系。
     * 通过这种方式，一个TypeHandler可以处理多种JDBC类型，或者作为特定Java类型的默认处理器。
     * <p>
     * 注解示例：
     * <pre>
     * @ MappedJdbcTypes({JdbcType.VARCHAR, JdbcType.CLOB})
     * public class StringTypeHandler extends BaseTypeHandler<String> {
     *   // 实现细节...
     * }
     * </pre>
     *
     * @param <T>         泛型类型，表示TypeHandler处理的Java类型
     * @param javaType    要注册的Java类型，可以是Class对象或ParameterizedType等
     * @param typeHandler 要注册的TypeHandler实例
     * @see MappedJdbcTypes
     * @see TypeHandler
     */
    private <T> void register(Type javaType, TypeHandler<? extends T> typeHandler) {
        // 获取TypeHandler类上的@MappedJdbcTypes注解，该注解指定了该处理器处理的JDBC类型
        MappedJdbcTypes mappedJdbcTypes = typeHandler.getClass().getAnnotation(MappedJdbcTypes.class);

        // 如果类上有@MappedJdbcTypes注解
        if (mappedJdbcTypes != null) {
            // 遍历注解中指定的所有JDBC类型
            for (JdbcType handledJdbcType : mappedJdbcTypes.value()) {
                // 将TypeHandler注册到每个指定的JDBC类型
                register(javaType, handledJdbcType, typeHandler);
            }
            // 如果注解中指定了包含null JDBC类型
            if (mappedJdbcTypes.includeNullJdbcType()) {
                // 将TypeHandler注册为默认处理器（JDBC类型为null）
                register(javaType, null, typeHandler);
            }
        } else {
            // 如果没有@MappedJdbcTypes注解，将TypeHandler注册为默认处理器（JDBC类型为null）
            register(javaType, null, typeHandler);
        }
    }


    public <T> void register(TypeReference<T> javaTypeReference, TypeHandler<? extends T> handler) {
        register(javaTypeReference.getRawType(), handler);
    }

    // java type + jdbc type + handler

    public <T> void register(Class<T> type, JdbcType jdbcType, TypeHandler<? extends T> handler) {
        register((Type) type, jdbcType, handler);
    }

    /**
     * 注册TypeHandler到指定的Java类型和JDBC类型
     * <p>
     * 该方法是TypeHandler注册体系的核心实现，负责将TypeHandler实例注册到指定的Java类型和JDBC类型组合。
     * 它会在TYPE_HANDLER_MAP中维护一个Java类型到JDBC类型到TypeHandler的映射关系，同时在ALL_TYPE_HANDLERS_MAP
     * 中保存所有TypeHandler实例，以便后续查找和使用。
     * <p>
     * 该方法是所有register重载方法的最终实现，其他方法最终都会调用此方法完成实际的注册工作。
     * <p>
     *
     * @param javaType 要注册的Java类型，可以是Class对象或ParameterizedType等
     * @param jdbcType 要注册的JDBC类型，如果为null则表示为该Java类型的默认处理器
     * @param handler  要注册的TypeHandler实例
     * @see TypeHandler
     * @see JdbcType
     */
    private void register(Type javaType, JdbcType jdbcType, TypeHandler<?> handler) {
        // 检查Java类型是否为null，null类型不需要注册
        if (javaType != null) {
            // 从TYPE_HANDLER_MAP中获取该Java类型对应的JDBC类型到TypeHandler的映射
            Map<JdbcType, TypeHandler<?>> map = TYPE_HANDLER_MAP.get(javaType);
            // 如果映射不存在或者是空映射，则创建一个新的HashMap
            if (map == null || map == NULL_TYPE_HANDLER_MAP) {
                // 创建新的HashMap用于存储JDBC类型到TypeHandler的映射
                map = new HashMap<JdbcType, TypeHandler<?>>();
                // 将新创建的映射放入TYPE_HANDLER_MAP中
                TYPE_HANDLER_MAP.put(javaType, map);
            }
            // 将JDBC类型和TypeHandler的映射关系放入map中
            map.put(jdbcType, handler);
        }
        // 将TypeHandler实例放入ALL_TYPE_HANDLERS_MAP中，以TypeHandler的Class对象为键
        ALL_TYPE_HANDLERS_MAP.put(handler.getClass(), handler);
    }

    //
    // REGISTER CLASS
    //

    // Only handler type

    /**
     * 注册TypeHandler类到类型处理器注册表
     * <p>
     * 该方法接收一个TypeHandler类的Class对象，并将其注册到类型处理器注册表中。
     * 注册过程会检查类上是否有{@code @MappedTypes}注解，如果有，则将该TypeHandler注册到
     * 注解中指定的所有Java类型；如果没有，则创建该TypeHandler的实例并直接注册。
     * <p>
     * 这种注册方式通常用于通过配置文件中的{@code <typeHandler>}元素注册类型处理器，
     * 或者在扫描包时发现TypeHandler类后进行注册。
     * <p>
     * 配置示例：
     * <pre>{@code
     * <typeHandlers>
     *   <typeHandler javaType="java.util.Date" jdbcType="TIMESTAMP"
     *                handler="com.example.DateTypeHandler"/>
     * </typeHandlers>
     * }</pre>
     *
     * @param typeHandlerClass 要注册的TypeHandler类的Class对象
     * @see MappedTypes
     * @see TypeHandler
     */
    public void register(Class<?> typeHandlerClass) {
        // 初始化标志，用于标记是否找到了映射的Java类型
        boolean mappedTypeFound = false;
        // 获取TypeHandler类上的@MappedTypes注解，该注解指定了该处理器处理的Java类型
        MappedTypes mappedTypes = typeHandlerClass.getAnnotation(MappedTypes.class);
        // 如果类上有@MappedTypes注解
        if (mappedTypes != null) {
            // 遍历注解中指定的所有Java类型
            for (Class<?> javaTypeClass : mappedTypes.value()) {
                // 将TypeHandler类注册到每个指定的Java类型
                register(javaTypeClass, typeHandlerClass);
                // 标记已找到映射的Java类型
                mappedTypeFound = true;
            }
        }
        // 如果没有找到映射的Java类型
        if (!mappedTypeFound) {
            // 创建TypeHandler实例并注册，不指定特定的Java类型
            register(getInstance(null, typeHandlerClass));
        }
    }

    // java type + handler type

    public void register(String javaTypeClassName, String typeHandlerClassName) throws ClassNotFoundException {
        register(Resources.classForName(javaTypeClassName), Resources.classForName(typeHandlerClassName));
    }

    public void register(Class<?> javaTypeClass, Class<?> typeHandlerClass) {
        register(javaTypeClass, getInstance(javaTypeClass, typeHandlerClass));
    }

    // java type + jdbc type + handler type

    public void register(Class<?> javaTypeClass, JdbcType jdbcType, Class<?> typeHandlerClass) {
        register(javaTypeClass, jdbcType, getInstance(javaTypeClass, typeHandlerClass));
    }

    // Construct a handler (used also from Builders)

    @SuppressWarnings("unchecked")
    public <T> TypeHandler<T> getInstance(Class<?> javaTypeClass, Class<?> typeHandlerClass) {
        if (javaTypeClass != null) {
            try {
                Constructor<?> c = typeHandlerClass.getConstructor(Class.class);
                return (TypeHandler<T>) c.newInstance(javaTypeClass);
            } catch (NoSuchMethodException ignored) {
                // ignored
            } catch (Exception e) {
                throw new TypeException("Failed invoking constructor for handler " + typeHandlerClass, e);
            }
        }
        try {
            Constructor<?> c = typeHandlerClass.getConstructor();
            return (TypeHandler<T>) c.newInstance();
        } catch (Exception e) {
            throw new TypeException("Unable to find a usable constructor for " + typeHandlerClass, e);
        }
    }

    // scan

    /**
     * 扫描指定包下的所有TypeHandler实现类并注册
     * <p>
     * 该方法通过反射机制扫描指定包名下的所有类，筛选出实现了{@code TypeHandler}接口的非抽象类，
     * 并将它们注册到类型处理器注册表中。这是批量注册类型处理器的便捷方法，
     * 通常在MyBatis初始化阶段通过配置文件中的{@code <package>}元素调用。
     * <p>
     * 该方法会忽略以下类型的类：
     * <ul>
     *   <li>匿名类</li>
     *   <li>接口</li>
     *   <li>抽象类</li>
     * </ul>
     * <p>
     * 配置示例：
     * <pre>{@code
     * <typeHandlers>
     *   <package name="com.example.typehandler"/>
     * </typeHandlers>
     * }</pre>
     *
     * @param packageName 要扫描的包名，用于查找TypeHandler实现类
     * @see TypeHandler
     * @see ResolverUtil
     */
    public void register(String packageName) {
        // 创建ResolverUtil实例，用于类扫描和解析
        ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<Class<?>>();
        // 使用IsA测试器查找实现了TypeHandler接口的类，限定在指定的包名下
        resolverUtil.find(new ResolverUtil.IsA(TypeHandler.class), packageName);
        // 获取扫描结果，即所有符合条件的类集合
        Set<Class<? extends Class<?>>> handlerSet = resolverUtil.getClasses();
        // 遍历所有找到的类
        for (Class<?> type : handlerSet) {
            // 忽略内部类、接口（包括package-info.java）和抽象类
            // 检查类是否不是匿名类、不是接口且不是抽象类
            if (!type.isAnonymousClass() && !type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
                // 注册符合条件的类型处理器类
                register(type);
            }
        }
    }


    // get information

    /**
     * @since 3.2.2
     */
    public Collection<TypeHandler<?>> getTypeHandlers() {
        return Collections.unmodifiableCollection(ALL_TYPE_HANDLERS_MAP.values());
    }

}
