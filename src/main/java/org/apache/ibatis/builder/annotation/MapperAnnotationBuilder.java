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

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.CacheNamespace;
import org.apache.ibatis.annotations.CacheNamespaceRef;
import org.apache.ibatis.annotations.Case;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Lang;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Options.FlushCachePolicy;
import org.apache.ibatis.annotations.Property;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.ResultType;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.TypeDiscriminator;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.UpdateProvider;
import org.apache.ibatis.binding.BindingException;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.FetchType;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.UnknownTypeHandler;

/**
 * @author Clinton Begin
 */
public class MapperAnnotationBuilder {

    private final Set<Class<? extends Annotation>> sqlAnnotationTypes = new HashSet<Class<? extends Annotation>>();
    private final Set<Class<? extends Annotation>> sqlProviderAnnotationTypes = new HashSet<Class<? extends Annotation>>();

    private final Configuration configuration;
    private final MapperBuilderAssistant assistant;
    private final Class<?> type;

    /**
     * 创建映射器注解构建器实例
     * <p>
     * 该构造方法用于初始化映射器注解构建器，设置配置对象、映射器接口类型以及
     * 预定义的SQL注解类型集合。构建器将使用这些信息来解析映射器接口上的注解，
     * 并将其转换为MyBatis可执行的MappedStatement对象。
     * </p>
     * <p>
     * 初始化过程：
     * <ol>
     *   <li>根据映射器接口类型创建资源标识符</li>
     *   <li>创建MapperBuilderAssistant辅助对象</li>
     *   <li>保存配置对象和映射器接口类型</li>
     *   <li>初始化SQL注解类型集合（@Select、@Insert、@Update、@Delete）</li>
     *   <li>初始化SQL提供者注解类型集合（@SelectProvider、@InsertProvider等）</li>
     * </ol>
     * </p>
     *
     * @param configuration MyBatis全局配置对象，包含所有配置信息
     * @param type          要解析的映射器接口的Class对象
     * @see MapperBuilderAssistant 用于辅助构建映射器的助手类
     * @see Select 查询注解
     * @see Insert 插入注解
     * @see Update 更新注解
     * @see Delete 删除注解
     * @see SelectProvider 查询提供者注解
     * @see InsertProvider 插入提供者注解
     * @see UpdateProvider 更新提供者注解
     * @see DeleteProvider 删除提供者注解
     */
    public MapperAnnotationBuilder(Configuration configuration, Class<?> type) {
        // 根据映射器接口类型创建资源标识符，将类名中的点替换为斜线，并添加.java后缀
        String resource = type.getName().replace('.', '/') + ".java (best guess)";
        // 创建映射器构建助手对象，用于辅助构建映射器
        this.assistant = new MapperBuilderAssistant(configuration, resource);
        // 保存MyBatis全局配置对象
        this.configuration = configuration;
        // 保存要解析的映射器接口类型
        this.type = type;

        // 初始化SQL注解类型集合，添加基本SQL操作注解
        sqlAnnotationTypes.add(Select.class);   // 添加查询注解类型
        sqlAnnotationTypes.add(Insert.class);   // 添加插入注解类型
        sqlAnnotationTypes.add(Update.class);   // 添加更新注解类型
        sqlAnnotationTypes.add(Delete.class);   // 添加删除注解类型

        // 初始化SQL提供者注解类型集合，添加动态SQL提供者注解
        sqlProviderAnnotationTypes.add(SelectProvider.class);    // 添加查询提供者注解类型
        sqlProviderAnnotationTypes.add(InsertProvider.class);    // 添加插入提供者注解类型
        sqlProviderAnnotationTypes.add(UpdateProvider.class);    // 添加更新提供者注解类型
        sqlProviderAnnotationTypes.add(DeleteProvider.class);    // 添加删除提供者注解类型
    }

    /**
     * 解析 Mapper 接口的注解配置
     * <p>
     * 该方法负责解析映射器接口上的所有注解配置，包括XML映射文件、缓存配置、
     * 方法注解等，并将这些配置转换为MyBatis内部的MappedStatement对象。
     * 解析过程遵循特定顺序，确保配置的正确性和完整性。
     * </p>
     * <p>
     * 按照以下顺序解析：XML 资源 -> 缓存配置 -> 方法注解 -> 未完成方法
     * </p>
     * <p>
     * 执行流程：
     * <ol>
     *   <li>检查映射器接口是否已解析，避免重复加载</li>
     *   <li>加载对应的XML映射文件（如果存在）</li>
     *   <li>标记资源为已加载状态</li>
     *   <li>设置当前命名空间为映射器接口的全限定名</li>
     *   <li>解析类级别的缓存配置（@CacheNamespace注解）</li>
     *   <li>解析类级别的缓存引用配置（@CacheNamespaceRef注解）</li>
     *   <li>遍历接口中的所有方法，解析方法上的SQL注解</li>
     *   <li>处理解析过程中未完成的方法，解决循环依赖问题</li>
     * </ol>
     * </p>
     * <p>
     * 确保每个 Mapper 接口只被解析一次，避免重复加载
     * </p>
     *
     * @see #loadXmlResource() 加载XML映射文件的方法
     * @see #parseCache() 解析缓存配置的方法
     * @see #parseCacheRef() 解析缓存引用配置的方法
     * @see #parseStatement(Method) 解析方法注解的方法
     * @see #parsePendingMethods() 解析未完成方法的方法
     */
    public void parse() {
        // 获取 Mapper 接口的资源标识符，用于检查是否已加载
        String resource = type.toString();

        // 检查该资源是否已经被加载过，避免重复解析
        if (!configuration.isResourceLoaded(resource)) {
            // 加载对应的 XML 映射文件（如果存在）
            loadXmlResource();

            // 标记该资源为已加载状态，防止重复加载
            configuration.addLoadedResource(resource);

            // 设置当前命名空间为 Mapper 接口的全限定名，用于标识映射器
            assistant.setCurrentNamespace(type.getName());

            // 解析类级别的缓存配置（@CacheNamespace 注解）
            parseCache();

            // 解析类级别的缓存引用配置（@CacheNamespaceRef 注解）
            parseCacheRef();

            // 获取 Mapper 接口中的所有方法，包括继承的方法
            Method[] methods = type.getMethods();

            // 遍历所有方法，解析方法上的 SQL 注解
            for (Method method : methods) {
                try {
                    // issue #237 - 跳过桥接方法（编译器生成的泛型桥接方法）
                    if (!method.isBridge()) {
                        // 解析方法上的 SQL 注解（@Select, @Insert, @Update, @Delete 等）
                        parseStatement(method);
                    }
                } catch (IncompleteElementException e) {
                    // 如果方法解析不完整（如依赖其他未解析的资源），
                    // 将其添加到未完成方法列表中，稍后重新解析
                    configuration.addIncompleteMethod(new MethodResolver(this, method));
                }
            }
        }

        // 解析之前未完成的方法（解决循环依赖问题）
        parsePendingMethods();
    }

    private void parsePendingMethods() {
        Collection<MethodResolver> incompleteMethods = configuration.getIncompleteMethods();
        synchronized (incompleteMethods) {
            Iterator<MethodResolver> iter = incompleteMethods.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().resolve();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // This method is still missing a resource
                }
            }
        }
    }

    /**
     * 加载映射器接口对应的XML映射文件
     * <p>
     * 该方法用于加载与映射器接口同名的XML映射文件，并使用XMLMapperBuilder进行解析。
     * 这是MyBatis混合使用注解和XML配置的关键机制，允许开发者在接口中使用注解定义简单SQL，
     * 同时在XML文件中定义复杂SQL和高级映射配置。
     * </p>
     * <p>
     * 执行流程：
     * <ol>
     *   <li>检查命名空间对应的资源是否已加载，避免重复加载</li>
     *   <li>构建XML文件路径（将类全限定名中的点替换为斜线，并添加.xml后缀）</li>
     *   <li>尝试从类路径加载XML文件资源</li>
     *   <li>如果资源存在，创建XMLMapperBuilder并解析XML文件</li>
     * </ol>
     * </p>
     * <p>
     * 注意事项：
     * <ul>
     *   <li>XML文件是可选的，如果不存在则忽略，不会抛出异常</li>
     *   <li>使用"namespace:"前缀标识资源，防止Spring等框架重复加载</li>
     *   <li>XML文件解析失败会导致BuilderException异常</li>
     *   <li>XML文件中的配置会覆盖注解中的同名配置</li>
     * </ul>
     * </p>
     *
     * @see XMLMapperBuilder XML映射文件构建器
     * @see Resources#getResourceAsStream(ClassLoader, String) 资源加载工具方法
     * @see Configuration#isResourceLoaded(String) 检查资源是否已加载
     */
    private void loadXmlResource() {
        // Spring可能不知道真实的资源名称，所以我们检查一个标志
        // 来防止两次加载同一个资源
        // 这个标志在XMLMapperBuilder#bindMapperForNamespace中设置
        if (!configuration.isResourceLoaded("namespace:" + type.getName())) {
            // 构建XML文件路径，将类全限定名中的点替换为斜线，并添加.xml后缀
            String xmlResource = type.getName().replace('.', '/') + ".xml";
            // 初始化输入流为null
            InputStream inputStream = null;
            try {
                // 尝试从类路径加载XML文件资源
                inputStream = Resources.getResourceAsStream(type.getClassLoader(), xmlResource);
            } catch (IOException e) {
                // 忽略异常，资源不是必需的
            }
            // 如果资源存在（输入流不为null）
            if (inputStream != null) {

                // 加载解析对应的mapper.xml文件，并且进行解析
                XMLMapperBuilder xmlParser = new XMLMapperBuilder(inputStream, assistant.getConfiguration(), xmlResource, configuration.getSqlFragments(), type.getName());
                // 解析XML映射文件
                xmlParser.parse();
            }
        }
    }

    /**
     * 解析映射器接口上的缓存配置注解
     * <p>
     * 该方法用于解析映射器接口上的@CacheNamespace注解，提取缓存相关配置，
     * 并根据这些配置创建二级缓存。缓存配置包括缓存实现类、淘汰策略、
     * 刷新间隔、缓存大小、读写属性和阻塞属性等。
     * </p>
     * <p>
     * 执行流程：
     * <ol>
     *   <li>获取映射器接口上的@CacheNamespace注解</li>
     *   <li>如果注解存在，提取缓存配置参数</li>
     *   <li>处理缓存大小和刷新间隔的默认值</li>
     *   <li>转换注解属性为Properties对象</li>
     *   <li>调用助手类创建新的缓存实例</li>
     * </ol>
     * </p>
     * <p>
     * 注意事项：
     * <ul>
     *   <li>如果映射器接口没有@CacheNamespace注解，则不创建缓存</li>
     *   <li>缓存大小和刷新间隔为0时，使用默认值</li>
     *   <li>属性配置支持变量替换</li>
     * </ul>
     * </p>
     *
     * @see CacheNamespace 缓存命名空间注解
     * @see MapperBuilderAssistant#useNewCache(Class, Class, Long, Integer, boolean, boolean, Properties) 创建缓存的方法
     * @see #convertToProperties(Property[]) 将注解属性转换为Properties对象的方法
     */
    private void parseCache() {
        // 获取映射器接口上的@CacheNamespace注解
        CacheNamespace cacheDomain = type.getAnnotation(CacheNamespace.class);
        // 如果注解存在，则进行缓存配置解析
        if (cacheDomain != null) {
            // 处理缓存大小，如果为0则使用默认值（null）
            Integer size = cacheDomain.size() == 0 ? null : cacheDomain.size();
            // 处理缓存刷新间隔，如果为0则使用默认值（null）
            Long flushInterval = cacheDomain.flushInterval() == 0 ? null : cacheDomain.flushInterval();
            // 将注解中的属性配置转换为Properties对象
            Properties props = convertToProperties(cacheDomain.properties());
            // 调用助手类创建新的缓存实例，传入所有缓存配置参数
            assistant.useNewCache(cacheDomain.implementation(), cacheDomain.eviction(), flushInterval, size, cacheDomain.readWrite(), cacheDomain.blocking(), props);
        }
    }


    private Properties convertToProperties(Property[] properties) {
        if (properties.length == 0) {
            return null;
        }
        Properties props = new Properties();
        for (Property property : properties) {
            props.setProperty(property.name(), PropertyParser.parse(property.value(), configuration.getVariables()));
        }
        return props;
    }

    /**
     * 解析映射器接口上的缓存引用配置注解
     * <p>
     * 该方法用于处理@CacheNamespaceRef注解，允许当前映射器接口引用其他命名空间的缓存实现，
     * 从而实现不同命名空间之间的缓存共享。这相当于XML配置中的&lt;cache-ref&gt;元素功能。
     * </p>
     * <p>
     * 执行流程：
     * <ol>
     *   <li>获取映射器接口上的@CacheNamespaceRef注解</li>
     *   <li>如果注解存在，提取value()和name()属性值</li>
     *   <li>验证注解属性配置的有效性</li>
     *   <li>根据属性值确定要引用的缓存命名空间</li>
     *   <li>调用助手类的useCacheRef方法建立缓存引用关系</li>
     * </ol>
     * </p>
     * <p>
     * 注意事项：
     * <ul>
     *   <li>@CacheNamespaceRef注解必须指定value()或name()属性中的一个，但不能同时指定</li>
     *   <li>value()属性用于指定引用缓存所在映射器接口的Class对象</li>
     *   <li>name()属性用于直接指定引用缓存的命名空间字符串</li>
     *   <li>如果引用的缓存不存在，将在后续处理中抛出IncompleteElementException</li>
     * </ul>
     * </p>
     *
     * @throws BuilderException 当@CacheNamespaceRef注解配置不当时抛出
     * @see CacheNamespaceRef 缓存引用注解
     * @see MapperBuilderAssistant#useCacheRef(String) 助手类中使用缓存引用的方法
     */
    private void parseCacheRef() {
        // 获取映射器接口上的@CacheNamespaceRef注解
        CacheNamespaceRef cacheDomainRef = type.getAnnotation(CacheNamespaceRef.class);

        // 如果注解存在，则进行处理
        if (cacheDomainRef != null) {
            // 获取注解中的value()属性，用于指定引用缓存所在映射器接口的Class对象
            Class<?> refType = cacheDomainRef.value();

            // 获取注解中的name()属性，用于直接指定引用缓存的命名空间字符串
            String refName = cacheDomainRef.name();

            // 验证注解配置：既没有指定value()也没有指定name()属性
            if (refType == void.class && refName.isEmpty()) {
                // 抛出异常，提示必须指定value()或name()属性中的一个
                throw new BuilderException("Should be specified either value() or name() attribute in the @CacheNamespaceRef");
            }

            // 验证注解配置：同时指定了value()和name()属性
            if (refType != void.class && !refName.isEmpty()) {
                // 抛出异常，提示不能同时指定value()和name()属性
                throw new BuilderException("Cannot use both value() and name() attribute in the @CacheNamespaceRef");
            }

            // 根据属性值确定要引用的缓存命名空间
            // 如果指定了value()属性，使用其全限定类名作为命名空间；否则使用name()属性值
            String namespace = (refType != void.class) ? refType.getName() : refName;

            // 调用助手类的useCacheRef方法，建立当前命名空间与指定缓存命名空间的引用关系
            assistant.useCacheRef(namespace);
        }
    }

    private String parseResultMap(Method method) {
        Class<?> returnType = getReturnType(method);
        ConstructorArgs args = method.getAnnotation(ConstructorArgs.class);
        Results results = method.getAnnotation(Results.class);
        TypeDiscriminator typeDiscriminator = method.getAnnotation(TypeDiscriminator.class);
        String resultMapId = generateResultMapName(method);
        applyResultMap(resultMapId, returnType, argsIf(args), resultsIf(results), typeDiscriminator);
        return resultMapId;
    }

    private String generateResultMapName(Method method) {
        Results results = method.getAnnotation(Results.class);
        if (results != null && !results.id().isEmpty()) {
            return type.getName() + "." + results.id();
        }
        StringBuilder suffix = new StringBuilder();
        for (Class<?> c : method.getParameterTypes()) {
            suffix.append("-");
            suffix.append(c.getSimpleName());
        }
        if (suffix.length() < 1) {
            suffix.append("-void");
        }
        return type.getName() + "." + method.getName() + suffix;
    }

    private void applyResultMap(String resultMapId, Class<?> returnType, Arg[] args, Result[] results, TypeDiscriminator discriminator) {
        List<ResultMapping> resultMappings = new ArrayList<ResultMapping>();
        applyConstructorArgs(args, returnType, resultMappings);
        applyResults(results, returnType, resultMappings);
        Discriminator disc = applyDiscriminator(resultMapId, returnType, discriminator);
        // TODO add AutoMappingBehaviour
        assistant.addResultMap(resultMapId, returnType, null, disc, resultMappings, null);
        createDiscriminatorResultMaps(resultMapId, returnType, discriminator);
    }

    private void createDiscriminatorResultMaps(String resultMapId, Class<?> resultType, TypeDiscriminator discriminator) {
        if (discriminator != null) {
            for (Case c : discriminator.cases()) {
                String caseResultMapId = resultMapId + "-" + c.value();
                List<ResultMapping> resultMappings = new ArrayList<ResultMapping>();
                // issue #136
                applyConstructorArgs(c.constructArgs(), resultType, resultMappings);
                applyResults(c.results(), resultType, resultMappings);
                // TODO add AutoMappingBehaviour
                assistant.addResultMap(caseResultMapId, c.type(), resultMapId, null, resultMappings, null);
            }
        }
    }

    private Discriminator applyDiscriminator(String resultMapId, Class<?> resultType, TypeDiscriminator discriminator) {
        if (discriminator != null) {
            String column = discriminator.column();
            Class<?> javaType = discriminator.javaType() == void.class ? String.class : discriminator.javaType();
            JdbcType jdbcType = discriminator.jdbcType() == JdbcType.UNDEFINED ? null : discriminator.jdbcType();
            @SuppressWarnings("unchecked") Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>) (discriminator.typeHandler() == UnknownTypeHandler.class ? null : discriminator.typeHandler());
            Case[] cases = discriminator.cases();
            Map<String, String> discriminatorMap = new HashMap<String, String>();
            for (Case c : cases) {
                String value = c.value();
                String caseResultMapId = resultMapId + "-" + value;
                discriminatorMap.put(value, caseResultMapId);
            }
            return assistant.buildDiscriminator(resultType, column, javaType, jdbcType, typeHandler, discriminatorMap);
        }
        return null;
    }

    /**
     * 解析 Mapper 接口中单个方法的注解配置，构建 MappedStatement
     * 该方法负责从方法注解中提取 SQL 语句、参数类型、返回类型、缓存策略等配置信息
     *
     * @param method Mapper 接口中的方法对象
     */
    void parseStatement(Method method) {
        // 获取方法的参数类型（排除 RowBounds 和 ResultHandler 等特殊参数）
        Class<?> parameterTypeClass = getParameterType(method);

        // 获取方法使用的语言驱动（支持自定义 SQL 语言）
        LanguageDriver languageDriver = getLanguageDriver(method);

        // 从方法注解中提取 SQL 源（@Select, @Insert, @Update, @Delete 等注解）
        SqlSource sqlSource = getSqlSourceFromAnnotations(method, parameterTypeClass, languageDriver);

        // 只有存在 SQL 注解时才继续解析
        if (sqlSource != null) {
            // 获取方法上的 @Options 注解配置
            Options options = method.getAnnotation(Options.class);

            // 构建 MappedStatement 的唯一标识符：接口全限定名.方法名
            final String mappedStatementId = type.getName() + "." + method.getName();

            // 设置默认的查询参数
            Integer fetchSize = null;
            Integer timeout = null;
            StatementType statementType = StatementType.PREPARED;
            ResultSetType resultSetType = ResultSetType.FORWARD_ONLY;

            // 根据 SQL 注解类型确定 SQL 命令类型
            SqlCommandType sqlCommandType = getSqlCommandType(method);
            boolean isSelect = sqlCommandType == SqlCommandType.SELECT;

            // 设置默认的缓存策略：SELECT 操作使用缓存，其他操作刷新缓存
            boolean flushCache = !isSelect;
            boolean useCache = isSelect;

            // 处理主键生成策略
            KeyGenerator keyGenerator;
            String keyProperty = "id";
            String keyColumn = null;

            // INSERT 和 UPDATE 操作需要特殊处理主键生成
            if (SqlCommandType.INSERT.equals(sqlCommandType) || SqlCommandType.UPDATE.equals(sqlCommandType)) {
                // first check for SelectKey annotation - that overrides everything else
                // 优先检查 @SelectKey 注解（优先级最高）
                SelectKey selectKey = method.getAnnotation(SelectKey.class);
                if (selectKey != null) {
                    keyGenerator = handleSelectKeyAnnotation(selectKey, mappedStatementId, getParameterType(method), languageDriver);
                    keyProperty = selectKey.keyProperty();
                }
                // 如果没有 @SelectKey 注解，根据配置决定是否使用自增主键
                else if (options == null) {
                    keyGenerator = configuration.isUseGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
                } else {
                    keyGenerator = options.useGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
                    keyProperty = options.keyProperty();
                    keyColumn = options.keyColumn();
                }
            } else {
                // 非 INSERT/UPDATE 操作不需要主键生成器
                keyGenerator = NoKeyGenerator.INSTANCE;
            }

            // 处理 @Options 注解中的配置（覆盖默认值）
            if (options != null) {
                // 处理缓存刷新策略
                if (FlushCachePolicy.TRUE.equals(options.flushCache())) {
                    flushCache = true;
                } else if (FlushCachePolicy.FALSE.equals(options.flushCache())) {
                    flushCache = false;
                }
                useCache = options.useCache();

                // issue #348 - 处理 fetchSize 的特殊值（Integer.MIN_VALUE）
                fetchSize = options.fetchSize() > -1 || options.fetchSize() == Integer.MIN_VALUE ? options.fetchSize() : null;
                timeout = options.timeout() > -1 ? options.timeout() : null;
                statementType = options.statementType();
                resultSetType = options.resultSetType();
            }

            // 处理结果映射配置
            String resultMapId = null;
            ResultMap resultMapAnnotation = method.getAnnotation(ResultMap.class);
            if (resultMapAnnotation != null) {
                // 使用 @ResultMap 注解指定的结果映射
                String[] resultMaps = resultMapAnnotation.value();
                StringBuilder sb = new StringBuilder();
                for (String resultMap : resultMaps) {
                    if (sb.length() > 0) {
                        sb.append(",");
                    }
                    sb.append(resultMap);
                }
                resultMapId = sb.toString();
            } else if (isSelect) {
                // 对于 SELECT 操作，如果没有指定 @ResultMap，则自动生成结果映射
                resultMapId = parseResultMap(method);
            }

            // 构建并添加 MappedStatement 到配置中
            assistant.addMappedStatement(mappedStatementId, sqlSource, statementType, sqlCommandType, fetchSize, timeout,
                    // ParameterMapID - 已弃用，设为 null
                    null, parameterTypeClass, resultMapId, getReturnType(method), resultSetType, flushCache, useCache,
                    // TODO gcode issue #577 - 延迟加载标志
                    false, keyGenerator, keyProperty, keyColumn,
                    // DatabaseID - 数据库标识符
                    null, languageDriver,
                    // ResultSets - 多结果集配置
                    options != null ? nullOrEmpty(options.resultSets()) : null);
        }
    }

    private LanguageDriver getLanguageDriver(Method method) {
        Lang lang = method.getAnnotation(Lang.class);
        Class<?> langClass = null;
        if (lang != null) {
            langClass = lang.value();
        }
        return assistant.getLanguageDriver(langClass);
    }

    private Class<?> getParameterType(Method method) {
        Class<?> parameterType = null;
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (Class<?> currentParameterType : parameterTypes) {
            if (!RowBounds.class.isAssignableFrom(currentParameterType) && !ResultHandler.class.isAssignableFrom(currentParameterType)) {
                if (parameterType == null) {
                    parameterType = currentParameterType;
                } else {
                    // issue #135
                    parameterType = ParamMap.class;
                }
            }
        }
        return parameterType;
    }

    private Class<?> getReturnType(Method method) {
        Class<?> returnType = method.getReturnType();
        Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, type);
        if (resolvedReturnType instanceof Class) {
            returnType = (Class<?>) resolvedReturnType;
            if (returnType.isArray()) {
                returnType = returnType.getComponentType();
            }
            // gcode issue #508
            if (void.class.equals(returnType)) {
                ResultType rt = method.getAnnotation(ResultType.class);
                if (rt != null) {
                    returnType = rt.value();
                }
            }
        } else if (resolvedReturnType instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) resolvedReturnType;
            Class<?> rawType = (Class<?>) parameterizedType.getRawType();
            if (Collection.class.isAssignableFrom(rawType) || Cursor.class.isAssignableFrom(rawType)) {
                Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
                if (actualTypeArguments != null && actualTypeArguments.length == 1) {
                    Type returnTypeParameter = actualTypeArguments[0];
                    if (returnTypeParameter instanceof Class<?>) {
                        returnType = (Class<?>) returnTypeParameter;
                    } else if (returnTypeParameter instanceof ParameterizedType) {
                        // (gcode issue #443) actual type can be a also a parameterized type
                        returnType = (Class<?>) ((ParameterizedType) returnTypeParameter).getRawType();
                    } else if (returnTypeParameter instanceof GenericArrayType) {
                        Class<?> componentType = (Class<?>) ((GenericArrayType) returnTypeParameter).getGenericComponentType();
                        // (gcode issue #525) support List<byte[]>
                        returnType = Array.newInstance(componentType, 0).getClass();
                    }
                }
            } else if (method.isAnnotationPresent(MapKey.class) && Map.class.isAssignableFrom(rawType)) {
                // (gcode issue 504) Do not look into Maps if there is not MapKey annotation
                Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
                if (actualTypeArguments != null && actualTypeArguments.length == 2) {
                    Type returnTypeParameter = actualTypeArguments[1];
                    if (returnTypeParameter instanceof Class<?>) {
                        returnType = (Class<?>) returnTypeParameter;
                    } else if (returnTypeParameter instanceof ParameterizedType) {
                        // (gcode issue 443) actual type can be a also a parameterized type
                        returnType = (Class<?>) ((ParameterizedType) returnTypeParameter).getRawType();
                    }
                }
            }
        }

        return returnType;
    }

    private SqlSource getSqlSourceFromAnnotations(Method method, Class<?> parameterType, LanguageDriver languageDriver) {
        try {
            Class<? extends Annotation> sqlAnnotationType = getSqlAnnotationType(method);
            Class<? extends Annotation> sqlProviderAnnotationType = getSqlProviderAnnotationType(method);
            if (sqlAnnotationType != null) {
                if (sqlProviderAnnotationType != null) {
                    throw new BindingException("You cannot supply both a static SQL and SqlProvider to method named " + method.getName());
                }
                Annotation sqlAnnotation = method.getAnnotation(sqlAnnotationType);
                final String[] strings = (String[]) sqlAnnotation.getClass().getMethod("value").invoke(sqlAnnotation);
                return buildSqlSourceFromStrings(strings, parameterType, languageDriver);
            } else if (sqlProviderAnnotationType != null) {
                Annotation sqlProviderAnnotation = method.getAnnotation(sqlProviderAnnotationType);
                return new ProviderSqlSource(assistant.getConfiguration(), sqlProviderAnnotation, type, method);
            }
            return null;
        } catch (Exception e) {
            throw new BuilderException("Could not find value method on SQL annotation.  Cause: " + e, e);
        }
    }

    private SqlSource buildSqlSourceFromStrings(String[] strings, Class<?> parameterTypeClass, LanguageDriver languageDriver) {
        final StringBuilder sql = new StringBuilder();
        for (String fragment : strings) {
            sql.append(fragment);
            sql.append(" ");
        }
        return languageDriver.createSqlSource(configuration, sql.toString().trim(), parameterTypeClass);
    }

    private SqlCommandType getSqlCommandType(Method method) {
        Class<? extends Annotation> type = getSqlAnnotationType(method);

        if (type == null) {
            type = getSqlProviderAnnotationType(method);

            if (type == null) {
                return SqlCommandType.UNKNOWN;
            }

            if (type == SelectProvider.class) {
                type = Select.class;
            } else if (type == InsertProvider.class) {
                type = Insert.class;
            } else if (type == UpdateProvider.class) {
                type = Update.class;
            } else if (type == DeleteProvider.class) {
                type = Delete.class;
            }
        }

        return SqlCommandType.valueOf(type.getSimpleName().toUpperCase(Locale.ENGLISH));
    }

    private Class<? extends Annotation> getSqlAnnotationType(Method method) {
        return chooseAnnotationType(method, sqlAnnotationTypes);
    }

    private Class<? extends Annotation> getSqlProviderAnnotationType(Method method) {
        return chooseAnnotationType(method, sqlProviderAnnotationTypes);
    }

    private Class<? extends Annotation> chooseAnnotationType(Method method, Set<Class<? extends Annotation>> types) {
        for (Class<? extends Annotation> type : types) {
            Annotation annotation = method.getAnnotation(type);
            if (annotation != null) {
                return type;
            }
        }
        return null;
    }

    private void applyResults(Result[] results, Class<?> resultType, List<ResultMapping> resultMappings) {
        for (Result result : results) {
            List<ResultFlag> flags = new ArrayList<ResultFlag>();
            if (result.id()) {
                flags.add(ResultFlag.ID);
            }
            @SuppressWarnings("unchecked") Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>) ((result.typeHandler() == UnknownTypeHandler.class) ? null : result.typeHandler());
            ResultMapping resultMapping = assistant.buildResultMapping(resultType, nullOrEmpty(result.property()), nullOrEmpty(result.column()), result.javaType() == void.class ? null : result.javaType(), result.jdbcType() == JdbcType.UNDEFINED ? null : result.jdbcType(), hasNestedSelect(result) ? nestedSelectId(result) : null, null, null, null, typeHandler, flags, null, null, isLazy(result));
            resultMappings.add(resultMapping);
        }
    }

    private String nestedSelectId(Result result) {
        String nestedSelect = result.one().select();
        if (nestedSelect.length() < 1) {
            nestedSelect = result.many().select();
        }
        if (!nestedSelect.contains(".")) {
            nestedSelect = type.getName() + "." + nestedSelect;
        }
        return nestedSelect;
    }

    private boolean isLazy(Result result) {
        boolean isLazy = configuration.isLazyLoadingEnabled();
        if (result.one().select().length() > 0 && FetchType.DEFAULT != result.one().fetchType()) {
            isLazy = result.one().fetchType() == FetchType.LAZY;
        } else if (result.many().select().length() > 0 && FetchType.DEFAULT != result.many().fetchType()) {
            isLazy = result.many().fetchType() == FetchType.LAZY;
        }
        return isLazy;
    }

    private boolean hasNestedSelect(Result result) {
        if (result.one().select().length() > 0 && result.many().select().length() > 0) {
            throw new BuilderException("Cannot use both @One and @Many annotations in the same @Result");
        }
        return result.one().select().length() > 0 || result.many().select().length() > 0;
    }

    private void applyConstructorArgs(Arg[] args, Class<?> resultType, List<ResultMapping> resultMappings) {
        for (Arg arg : args) {
            List<ResultFlag> flags = new ArrayList<ResultFlag>();
            flags.add(ResultFlag.CONSTRUCTOR);
            if (arg.id()) {
                flags.add(ResultFlag.ID);
            }
            @SuppressWarnings("unchecked") Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>) (arg.typeHandler() == UnknownTypeHandler.class ? null : arg.typeHandler());
            ResultMapping resultMapping = assistant.buildResultMapping(resultType, nullOrEmpty(arg.name()), nullOrEmpty(arg.column()), arg.javaType() == void.class ? null : arg.javaType(), arg.jdbcType() == JdbcType.UNDEFINED ? null : arg.jdbcType(), nullOrEmpty(arg.select()), nullOrEmpty(arg.resultMap()), null, null, typeHandler, flags, null, null, false);
            resultMappings.add(resultMapping);
        }
    }

    private String nullOrEmpty(String value) {
        return value == null || value.trim().length() == 0 ? null : value;
    }

    private Result[] resultsIf(Results results) {
        return results == null ? new Result[0] : results.value();
    }

    private Arg[] argsIf(ConstructorArgs args) {
        return args == null ? new Arg[0] : args.value();
    }

    private KeyGenerator handleSelectKeyAnnotation(SelectKey selectKeyAnnotation, String baseStatementId, Class<?> parameterTypeClass, LanguageDriver languageDriver) {
        String id = baseStatementId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
        Class<?> resultTypeClass = selectKeyAnnotation.resultType();
        StatementType statementType = selectKeyAnnotation.statementType();
        String keyProperty = selectKeyAnnotation.keyProperty();
        String keyColumn = selectKeyAnnotation.keyColumn();
        boolean executeBefore = selectKeyAnnotation.before();

        // defaults
        boolean useCache = false;
        KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
        Integer fetchSize = null;
        Integer timeout = null;
        boolean flushCache = false;
        String parameterMap = null;
        String resultMap = null;
        ResultSetType resultSetTypeEnum = null;

        SqlSource sqlSource = buildSqlSourceFromStrings(selectKeyAnnotation.statement(), parameterTypeClass, languageDriver);
        SqlCommandType sqlCommandType = SqlCommandType.SELECT;

        assistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType, fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass, resultSetTypeEnum, flushCache, useCache, false, keyGenerator, keyProperty, keyColumn, null, languageDriver, null);

        id = assistant.applyCurrentNamespace(id, false);

        MappedStatement keyStatement = configuration.getMappedStatement(id, false);
        SelectKeyGenerator answer = new SelectKeyGenerator(keyStatement, executeBefore);
        configuration.addKeyGenerator(id, answer);
        return answer;
    }

}