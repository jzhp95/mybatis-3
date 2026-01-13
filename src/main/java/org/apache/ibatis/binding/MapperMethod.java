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

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

import org.apache.ibatis.annotations.Flush;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 */
public class MapperMethod {

    private final SqlCommand command;
    private final MethodSignature method;

    public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {
        // 解析 SQL 命令类型、参数映射、结果映射等
        this.command = new SqlCommand(config, mapperInterface, method);
        // 解析方法签名，包括参数映射、返回值类型等
        this.method = new MethodSignature(config, mapperInterface, method);
    }

    /**
     * 执行 SQL 操作（INSERT、UPDATE、DELETE、SELECT）。
     *
     * @param sqlSession MyBatis SqlSession 实例，用于执行 SQL 操作。
     * @param args       方法调用时传递的参数数组，用于构建 SQL 命令参数。
     * @return 操作执行结果，根据方法签名的返回值类型不同而变化。
     */
    public Object execute(SqlSession sqlSession, Object[] args) {
        Object result;
        switch (command.getType()) {
            case INSERT: {
                Object param = method.convertArgsToSqlCommandParam(args);
                result = rowCountResult(sqlSession.insert(command.getName(), param));
                break;
            }
            case UPDATE: {
                Object param = method.convertArgsToSqlCommandParam(args);
                result = rowCountResult(sqlSession.update(command.getName(), param));
                break;
            }
            case DELETE: {
                Object param = method.convertArgsToSqlCommandParam(args);
                result = rowCountResult(sqlSession.delete(command.getName(), param));
                break;
            }
            case SELECT:
                // 处理返回值为 void 类型且有 ResultHandler 参数的情况
                if (method.returnsVoid() && method.hasResultHandler()) {
                    executeWithResultHandler(sqlSession, args);
                    result = null;
                }
                // 处理返回值为集合类型（List、Set）的情况
                else if (method.returnsMany()) {
                    result = executeForMany(sqlSession, args);
                }
                // 处理返回值为 Map 类型的情况
                else if (method.returnsMap()) {
                    result = executeForMap(sqlSession, args);
                }
                // 处理返回值为 Cursor 类型的情况
                else if (method.returnsCursor()) {
                    result = executeForCursor(sqlSession, args);
                }
                // 处理其他情况，默认返回单条记录
                else {
                    // 解析参数，并将参数和参数名进行映射
                    // 规则：如果方法只有一个参数且没有使用 @Param 注解，直接返回该参数值
                    // 否则，将参数和参数名进行映射，构建参数 Map
                    Object param = method.convertArgsToSqlCommandParam(args);
                    // 实际上是委托给 sqlSession 去执行查询操作
                    result = sqlSession.selectOne(command.getName(), param);
                }
                break;
            case FLUSH:
                result = sqlSession.flushStatements();
                break;
            default:
                throw new BindingException("Unknown execution method for: " + command.getName());
        }
        if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
            throw new BindingException("Mapper method '" + command.getName()
                    + " attempted to return null from a method with a primitive return type (" + method.getReturnType() + ").");
        }
        return result;
    }

    private Object rowCountResult(int rowCount) {
        final Object result;
        if (method.returnsVoid()) {
            result = null;
        } else if (Integer.class.equals(method.getReturnType()) || Integer.TYPE.equals(method.getReturnType())) {
            result = rowCount;
        } else if (Long.class.equals(method.getReturnType()) || Long.TYPE.equals(method.getReturnType())) {
            result = (long) rowCount;
        } else if (Boolean.class.equals(method.getReturnType()) || Boolean.TYPE.equals(method.getReturnType())) {
            result = rowCount > 0;
        } else {
            throw new BindingException("Mapper method '" + command.getName() + "' has an unsupported return type: " + method.getReturnType());
        }
        return result;
    }

    private void executeWithResultHandler(SqlSession sqlSession, Object[] args) {
        MappedStatement ms = sqlSession.getConfiguration().getMappedStatement(command.getName());
        if (!StatementType.CALLABLE.equals(ms.getStatementType())
                && void.class.equals(ms.getResultMaps().get(0).getType())) {
            throw new BindingException("method " + command.getName()
                    + " needs either a @ResultMap annotation, a @ResultType annotation,"
                    + " or a resultType attribute in XML so a ResultHandler can be used as a parameter.");
        }
        Object param = method.convertArgsToSqlCommandParam(args);
        if (method.hasRowBounds()) {
            RowBounds rowBounds = method.extractRowBounds(args);
            sqlSession.select(command.getName(), param, rowBounds, method.extractResultHandler(args));
        } else {
            sqlSession.select(command.getName(), param, method.extractResultHandler(args));
        }
    }

    private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {
        List<E> result;
        Object param = method.convertArgsToSqlCommandParam(args);
        if (method.hasRowBounds()) {
            RowBounds rowBounds = method.extractRowBounds(args);
            result = sqlSession.<E>selectList(command.getName(), param, rowBounds);
        } else {
            result = sqlSession.<E>selectList(command.getName(), param);
        }
        // issue #510 Collections & arrays support
        if (!method.getReturnType().isAssignableFrom(result.getClass())) {
            if (method.getReturnType().isArray()) {
                return convertToArray(result);
            } else {
                return convertToDeclaredCollection(sqlSession.getConfiguration(), result);
            }
        }
        return result;
    }

    private <T> Cursor<T> executeForCursor(SqlSession sqlSession, Object[] args) {
        Cursor<T> result;
        Object param = method.convertArgsToSqlCommandParam(args);
        if (method.hasRowBounds()) {
            RowBounds rowBounds = method.extractRowBounds(args);
            result = sqlSession.<T>selectCursor(command.getName(), param, rowBounds);
        } else {
            result = sqlSession.<T>selectCursor(command.getName(), param);
        }
        return result;
    }

    private <E> Object convertToDeclaredCollection(Configuration config, List<E> list) {
        Object collection = config.getObjectFactory().create(method.getReturnType());
        MetaObject metaObject = config.newMetaObject(collection);
        metaObject.addAll(list);
        return collection;
    }

    @SuppressWarnings("unchecked")
    private <E> Object convertToArray(List<E> list) {
        Class<?> arrayComponentType = method.getReturnType().getComponentType();
        Object array = Array.newInstance(arrayComponentType, list.size());
        if (arrayComponentType.isPrimitive()) {
            for (int i = 0; i < list.size(); i++) {
                Array.set(array, i, list.get(i));
            }
            return array;
        } else {
            return list.toArray((E[]) array);
        }
    }

    private <K, V> Map<K, V> executeForMap(SqlSession sqlSession, Object[] args) {
        Map<K, V> result;
        Object param = method.convertArgsToSqlCommandParam(args);
        if (method.hasRowBounds()) {
            RowBounds rowBounds = method.extractRowBounds(args);
            result = sqlSession.<K, V>selectMap(command.getName(), param, method.getMapKey(), rowBounds);
        } else {
            result = sqlSession.<K, V>selectMap(command.getName(), param, method.getMapKey());
        }
        return result;
    }

    public static class ParamMap<V> extends HashMap<String, V> {

        private static final long serialVersionUID = -2212268410512043556L;

        @Override
        public V get(Object key) {
            if (!super.containsKey(key)) {
                throw new BindingException("Parameter '" + key + "' not found. Available parameters are " + keySet());
            }
            return super.get(key);
        }

    }

    /**
     * SQL命令封装类，用于存储Mapper接口方法对应的SQL语句ID和命令类型。
     * <p>
     * 该内部类负责将Mapper接口方法与MyBatis配置中的MappedStatement进行关联，
     * 确定方法对应的SQL命令类型（INSERT、UPDATE、DELETE、SELECT或FLUSH）。
     */
    public static class SqlCommand {

        /**
         * SQL语句的唯一标识符，格式为"接口全限定名.方法名"
         */
        private final String name;

        /**
         * SQL命令类型，包括INSERT、UPDATE、DELETE、SELECT、FLUSH等
         */
        private final SqlCommandType type;

        /**
         * 构造函数，根据Mapper接口方法信息创建SqlCommand对象。
         *
         * @param configuration   MyBatis配置对象
         * @param mapperInterface Mapper接口类
         * @param method          接口方法对象
         * @throws BindingException 当无法找到对应的MappedStatement或命令类型未知时抛出
         */
        public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {
            // 获取方法名称和声明类
            final String methodName = method.getName();
            final Class<?> declaringClass = method.getDeclaringClass();

            // 尝试解析方法对应的MappedStatement
            // 说明：MappedStatement 是 MyBatis 中用于存储 SQL 映射信息的类，在解析配置文件的时候
            // 解析到mapper文件时，会将每个SQL语句解析为一个MappedStatement对象
            // 并将其存储在Configuration的mappedStatements映射中，
            // 键为SQL语句的唯一标识符（格式为"接口全限定名.方法名"）
            MappedStatement ms = resolveMappedStatement(mapperInterface, methodName, declaringClass, configuration);

            if (ms == null) {
                // 如果没有找到MappedStatement，检查是否为Flush注解方法
                if (method.getAnnotation(Flush.class) != null) {
                    name = null;
                    type = SqlCommandType.FLUSH;
                } else {
                    // 既不是Flush方法也没有对应的SQL语句，抛出绑定异常
                    throw new BindingException("Invalid bound statement (not found): "
                            + mapperInterface.getName() + "." + methodName);
                }
            } else {
                // 找到MappedStatement，设置名称和类型
                name = ms.getId();
                type = ms.getSqlCommandType();

                // 检查命令类型是否有效
                if (type == SqlCommandType.UNKNOWN) {
                    throw new BindingException("Unknown execution method for: " + name);
                }
            }
        }

        /**
         * 获取SQL语句的唯一标识符。
         *
         * @return SQL语句ID，格式为"接口全限定名.方法名"，对于FLUSH类型返回null
         */
        public String getName() {
            return name;
        }

        /**
         * 获取SQL命令类型。
         *
         * @return SQL命令类型枚举值，包括INSERT、UPDATE、DELETE、SELECT、FLUSH等
         */
        public SqlCommandType getType() {
            return type;
        }

        /**
         * 解析Mapper接口方法对应的MappedStatement。
         * <p>
         * 该方法会首先尝试在当前接口中查找对应的MappedStatement，
         * 如果找不到，则会递归查找父接口中是否有对应的MappedStatement。
         *
         * @param mapperInterface Mapper接口类
         * @param methodName      方法名称
         * @param declaringClass  声明该方法的类
         * @param configuration   MyBatis配置对象
         * @return 找到的MappedStatement对象，如果找不到则返回null
         */
        private MappedStatement resolveMappedStatement(Class<?> mapperInterface, String methodName,
                                                       Class<?> declaringClass, Configuration configuration) {
            // 构建语句ID：接口全限定名.方法名
            String statementId = mapperInterface.getName() + "." + methodName;

            // 检查配置中是否存在该语句ID
            if (configuration.hasStatement(statementId)) {
                return configuration.getMappedStatement(statementId);
            }
            // 如果方法是在当前接口中声明的，且没有找到对应的MappedStatement，返回null
            else if (mapperInterface.equals(declaringClass)) {
                return null;
            }

            // 递归检查父接口中是否有对应的MappedStatement
            for (Class<?> superInterface : mapperInterface.getInterfaces()) {
                if (declaringClass.isAssignableFrom(superInterface)) {
                    MappedStatement ms = resolveMappedStatement(superInterface, methodName, declaringClass, configuration);
                    if (ms != null) {
                        return ms;
                    }
                }
            }
            return null;
        }
    }


    /**
     * 方法签名封装类，用于解析和存储Mapper接口方法的签名信息。
     * <p>
     * 该内部类负责分析方法的返回类型、参数类型、特殊参数（如RowBounds和ResultHandler）等，
     * 为后续的SQL执行提供必要的元数据信息。
     */
    public static class MethodSignature {

        /**
         * 标识方法是否返回集合或数组类型
         */
        private final boolean returnsMany;

        /**
         * 标识方法是否返回Map类型
         */
        private final boolean returnsMap;

        /**
         * 标识方法是否返回void类型
         */
        private final boolean returnsVoid;

        /**
         * 标识方法是否返回Cursor类型
         */
        private final boolean returnsCursor;

        /**
         * 方法的返回类型
         */
        private final Class<?> returnType;

        /**
         * 当返回类型为Map时，指定作为键的属性名
         */
        private final String mapKey;

        /**
         * ResultHandler参数在参数列表中的索引位置
         */
        private final Integer resultHandlerIndex;

        /**
         * RowBounds参数在参数列表中的索引位置
         */
        private final Integer rowBoundsIndex;

        /**
         * 参数名称解析器，用于处理方法参数的命名
         */
        private final ParamNameResolver paramNameResolver;

        /**
         * 构造函数，解析方法签名并初始化所有字段。
         *
         * @param configuration   MyBatis配置对象
         * @param mapperInterface Mapper接口类
         * @param method          接口方法对象
         */
        public MethodSignature(Configuration configuration, Class<?> mapperInterface, Method method) {
            // 解析方法的返回类型，考虑泛型参数
            Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, mapperInterface);
            if (resolvedReturnType instanceof Class<?>) {
                // 普通类类型
                this.returnType = (Class<?>) resolvedReturnType;
            } else if (resolvedReturnType instanceof ParameterizedType) {
                // 参数化类型（如List<String>）
                this.returnType = (Class<?>) ((ParameterizedType) resolvedReturnType).getRawType();
            } else {
                // 其他类型，直接使用方法的返回类型
                this.returnType = method.getReturnType();
            }

            // 是否返回void类型
            this.returnsVoid = void.class.equals(this.returnType);
            // 是否返回集合或数组类型
            this.returnsMany = configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray();
            // 是否返回Cursor类型
            this.returnsCursor = Cursor.class.equals(this.returnType);

            // 解析Map类型的键属性
            this.mapKey = getMapKey(method);
            // 是否返回Map类型
            this.returnsMap = this.mapKey != null;

            // 查找特殊参数的索引位置
            this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class);
            this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class);

            // 创建参数名称解析器
            // 解析方法的参数信息并初始化参数名称映射。
            this.paramNameResolver = new ParamNameResolver(configuration, method);
        }

        /**
         * 将方法参数转换为SQL命令参数。
         *
         * @param args 方法参数数组
         * @return 转换后的参数Map，键为参数名，值为参数值
         */
        public Object convertArgsToSqlCommandParam(Object[] args) {
            return paramNameResolver.getNamedParams(args);
        }

        /**
         * 检查方法是否包含RowBounds参数。
         *
         * @return 如果包含RowBounds参数返回true，否则返回false
         */
        public boolean hasRowBounds() {
            return rowBoundsIndex != null;
        }

        /**
         * 从参数数组中提取RowBounds对象。
         *
         * @param args 方法参数数组
         * @return RowBounds对象，如果方法不包含该参数则返回null
         */
        public RowBounds extractRowBounds(Object[] args) {
            return hasRowBounds() ? (RowBounds) args[rowBoundsIndex] : null;
        }

        /**
         * 检查方法是否包含ResultHandler参数。
         *
         * @return 如果包含ResultHandler参数返回true，否则返回false
         */
        public boolean hasResultHandler() {
            return resultHandlerIndex != null;
        }

        /**
         * 从参数数组中提取ResultHandler对象。
         *
         * @param args 方法参数数组
         * @return ResultHandler对象，如果方法不包含该参数则返回null
         */
        public ResultHandler extractResultHandler(Object[] args) {
            return hasResultHandler() ? (ResultHandler) args[resultHandlerIndex] : null;
        }

        /**
         * 获取Map类型的键属性名。
         *
         * @return Map键属性名，如果方法返回类型不是Map则返回null
         */
        public String getMapKey() {
            return mapKey;
        }

        /**
         * 获取方法的返回类型。
         *
         * @return 方法的返回类型Class对象
         */
        public Class<?> getReturnType() {
            return returnType;
        }

        /**
         * 检查方法是否返回集合或数组类型。
         *
         * @return 如果返回集合或数组类型返回true，否则返回false
         */
        public boolean returnsMany() {
            return returnsMany;
        }

        /**
         * 检查方法是否返回Map类型。
         *
         * @return 如果返回Map类型返回true，否则返回false
         */
        public boolean returnsMap() {
            return returnsMap;
        }

        /**
         * 检查方法是否返回void类型。
         *
         * @return 如果返回void类型返回true，否则返回false
         */
        public boolean returnsVoid() {
            return returnsVoid;
        }

        /**
         * 检查方法是否返回Cursor类型。
         *
         * @return 如果返回Cursor类型返回true，否则返回false
         */
        public boolean returnsCursor() {
            return returnsCursor;
        }

        /**
         * 获取指定类型参数在方法参数列表中的唯一索引位置。
         *
         * @param method    方法对象
         * @param paramType 要查找的参数类型
         * @return 参数索引位置，如果找不到或存在多个同类型参数则返回null
         * @throws BindingException 当存在多个同类型参数时抛出异常
         */
        private Integer getUniqueParamIndex(Method method, Class<?> paramType) {
            Integer index = null;
            final Class<?>[] argTypes = method.getParameterTypes();

            // 遍历所有参数类型
            for (int i = 0; i < argTypes.length; i++) {
                if (paramType.isAssignableFrom(argTypes[i])) {
                    if (index == null) {
                        // 找到第一个匹配的参数
                        index = i;
                    } else {
                        // 发现多个同类型参数，抛出异常
                        throw new BindingException(method.getName() + " cannot have multiple " + paramType.getSimpleName() + " parameters");
                    }
                }
            }
            return index;
        }

        /**
         * 获取Map返回类型的键属性名。
         * <p>
         * 该方法检查方法是否返回Map类型，如果是，则查找@MapKey注解
         * 获取指定的键属性名。
         *
         * @param method 方法对象
         * @return Map键属性名，如果没有@MapKey注解或返回类型不是Map则返回null
         */
        private String getMapKey(Method method) {
            String mapKey = null;
            // 检查返回类型是否为Map或其子类
            if (Map.class.isAssignableFrom(method.getReturnType())) {
                // 获取@MapKey注解
                final MapKey mapKeyAnnotation = method.getAnnotation(MapKey.class);
                if (mapKeyAnnotation != null) {
                    mapKey = mapKeyAnnotation.value();
                }
            }
            return mapKey;
        }
    }

}
