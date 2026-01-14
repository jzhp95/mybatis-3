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
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 */
public class XMLMapperBuilder extends BaseBuilder {

    private final XPathParser parser;
    private final MapperBuilderAssistant builderAssistant;
    private final Map<String, XNode> sqlFragments;
    private final String resource;

    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
        this(reader, configuration, resource, sqlFragments);
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
                configuration, resource, sqlFragments);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
        this(inputStream, configuration, resource, sqlFragments);
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
                configuration, resource, sqlFragments);
    }

    private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        super(configuration);
        this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
        this.parser = parser;
        this.sqlFragments = sqlFragments;
        this.resource = resource;
    }

    /**
     * 解析映射器 XML 文件并构建 MyBatis 配置
     * <p>
     * 这是 XMLMapperBuilder 类的核心方法，负责：
     * 1. 解析映射器 XML 文件中的各个配置元素
     * 2. 将解析结果注册到 Configuration 对象中
     * 3. 处理之前解析失败的元素（如依赖未满足的 ResultMap、CacheRef 等）
     * 4. 绑定命名空间对应的 Mapper 接口
     * <p>
     * 执行流程：
     * 1. 检查资源是否已加载，避免重复解析
     * 2. 解析映射器配置元素（缓存、参数映射、结果映射、SQL 片段、SQL 语句等）
     * 3. 标记资源为已加载
     * 4. 绑定命名空间对应的 Mapper 接口类
     * 5. 处理之前解析失败的 ResultMap
     * 6. 处理之前解析失败的 CacheRef
     * 7. 处理之前解析失败的 SQL 语句
     * <p>
     * 设计说明：
     * - 使用延迟解析机制处理元素间的依赖关系
     * - 通过资源加载状态检查避免重复解析
     * - 支持命名空间与 Mapper 接口的自动绑定
     * - 使用同步机制确保线程安全
     * <p>
     * 异常处理：
     * - 解析过程中的异常会被包装成 BuilderException
     * - 依赖未满足的元素会被添加到待处理列表，后续重试
     *
     * @throws BuilderException 如果解析过程中发生严重错误
     */
    public void parse() {
        // 检查资源是否已加载，避免重复解析同一个映射文件
        if (!configuration.isResourceLoaded(resource)) {
            // 解析映射器 XML 文件的根元素（/mapper），处理其中的各种配置
            configurationElement(parser.evalNode("/mapper"));
            // 标记当前资源为已加载，防止重复解析
            configuration.addLoadedResource(resource);
            // 绑定命名空间对应的 Mapper 接口类，实现 XML 与接口的关联
            bindMapperForNamespace();
        }

        // 处理之前解析失败的 ResultMap（可能由于依赖未满足）
        parsePendingResultMaps();
        // 处理之前解析失败的 CacheRef（可能由于引用的缓存未定义）
        parsePendingCacheRefs();
        // 处理之前解析失败的 SQL 语句（可能由于依赖未满足）
        parsePendingStatements();
    }

    public XNode getSqlFragment(String refid) {
        return sqlFragments.get(refid);
    }

    /**
     * 解析映射器 XML 文件的配置元素
     * <p>
     * 此方法负责解析映射器 XML 文件中的各种配置元素，包括：
     * 1. 命名空间设置
     * 2. 缓存引用配置（cache-ref）
     * 3. 缓存配置（cache）
     * 4. 参数映射配置（parameterMap）
     * 5. 结果映射配置（resultMap）
     * 6. SQL 片段配置（sql）
     * 7. SQL 语句配置（select/insert/update/delete）
     * <p>
     * 执行顺序说明：
     * 1. 首先解析命名空间，作为后续所有配置的上下文
     * 2. 然后解析缓存相关配置（cache-ref 和 cache），确保缓存优先级正确
     * 3. 接着解析映射配置（parameterMap 和 resultMap），为 SQL 语句提供映射基础
     * 4. 然后解析可重用的 SQL 片段，供 SQL 语句引用
     * 5. 最后解析具体的 SQL 语句，此时所有依赖元素都已解析完成
     * <p>
     * 异常处理：
     * - 命名空间为空时抛出 BuilderException
     * - 其他解析异常会被包装成 BuilderException 并包含资源位置信息
     *
     * @param context 映射器 XML 文件的根节点（/mapper）
     * @throws BuilderException 当解析过程中发生错误时抛出
     */
    private void configurationElement(XNode context) {
        try {
            // 获取并验证命名空间，命名空间是映射器的唯一标识
            String namespace = context.getStringAttribute("namespace");
            if (namespace == null || namespace.equals("")) {
                throw new BuilderException("Mapper's namespace cannot be empty");
            }
            // 设置当前命名空间，作为后续所有配置的上下文
            builderAssistant.setCurrentNamespace(namespace);

            // 解析缓存引用配置，引用其他命名空间的缓存
            cacheRefElement(context.evalNode("cache-ref"));
            // 解析当前命名空间的缓存配置
            cacheElement(context.evalNode("cache"));

            // 解析参数映射配置（已废弃，推荐使用内联参数映射）
            parameterMapElement(context.evalNodes("/mapper/parameterMap"));
            // 解析结果映射配置，定义结果集与对象属性的映射规则
            resultMapElements(context.evalNodes("/mapper/resultMap"));

            // 解析可重用的 SQL 片段，供后续 SQL 语句引用
            sqlElement(context.evalNodes("/mapper/sql"));
            // 解析具体的 SQL 语句（select/insert/update/delete），构建 MappedStatement
            buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
        } catch (Exception e) {
            // 将解析异常包装成 BuilderException，并包含资源位置信息，便于调试
            throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
        }
    }

    private void buildStatementFromContext(List<XNode> list) {
        if (configuration.getDatabaseId() != null) {
            buildStatementFromContext(list, configuration.getDatabaseId());
        }

        buildStatementFromContext(list, null);
    }

    private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
        for (XNode context : list) {
            final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
            try {
                statementParser.parseStatementNode();
            } catch (IncompleteElementException e) {
                configuration.addIncompleteStatement(statementParser);
            }
        }
    }

    private void parsePendingResultMaps() {
        Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
        synchronized (incompleteResultMaps) {
            Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().resolve();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // ResultMap is still missing a resource...
                }
            }
        }
    }

    private void parsePendingCacheRefs() {
        Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
        synchronized (incompleteCacheRefs) {
            Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().resolveCacheRef();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Cache ref is still missing a resource...
                }
            }
        }
    }

    private void parsePendingStatements() {
        Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
        synchronized (incompleteStatements) {
            Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().parseStatementNode();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Statement is still missing a resource...
                }
            }
        }
    }

    private void cacheRefElement(XNode context) {
        if (context != null) {
            configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
            CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
            try {
                cacheRefResolver.resolveCacheRef();
            } catch (IncompleteElementException e) {
                configuration.addIncompleteCacheRef(cacheRefResolver);
            }
        }
    }

    /**
     * 解析映射器 XML 文件中的缓存配置元素
     * <p>
     * 此方法负责解析 <cache> 元素，配置当前命名空间的二级缓存。
     * MyBatis 的二级缓存是跨 SqlSession 的，可以被多个 SqlSession 共享。
     * <p>
     * 缓存配置属性详解：
     * 1. type: 缓存实现类型，默认为 PERPETUAL（永久缓存）
     * 2. eviction: 缓存回收策略，默认为 LRU（最近最少使用）
     * 3. flushInterval: 缓存刷新间隔，单位毫秒
     * 4. size: 缓存大小，指缓存对象的数量
     * 5. readOnly: 是否只读，默认为 false（可读写）
     * 6. blocking: 是否阻塞，默认为 false（非阻塞）
     * 7. 其他自定义属性：通过子元素配置
     * <p>
     * 缓存类型（type）可选值：
     * - PERPETUAL: 永久缓存（默认）
     * - FIFO: 先进先出
     * - LRU: 最近最少使用
     * - SOFT: 软引用
     * - WEAK: 弱引用
     * <p>
     * 缓存回收策略（eviction）可选值：
     * - LRU: 最近最少使用（默认）
     * - FIFO: 先进先出
     * - SOFT: 软引用
     * - WEAK: 弱引用
     *
     * @param context <cache> 元素的 XNode 对象
     * @throws Exception 当解析过程中发生错误时抛出
     */
    private void cacheElement(XNode context) throws Exception {
        // 检查缓存元素是否存在，如果不存在则不进行缓存配置
        if (context != null) {
            // 解析缓存类型，默认为 PERPETUAL（永久缓存）
            String type = context.getStringAttribute("type", "PERPETUAL");
            // 将类型别名解析为实际的 Cache 实现类
            Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);

            // 解析缓存回收策略，默认为 LRU（最近最少使用）
            String eviction = context.getStringAttribute("eviction", "LRU");
            // 将回收策略别名解析为实际的 Cache 实现类
            Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);

            // 解析缓存刷新间隔，单位毫秒，默认为 null（不自动刷新）
            Long flushInterval = context.getLongAttribute("flushInterval");
            // 解析缓存大小，指缓存对象的数量，默认为 null（无限制）
            Integer size = context.getIntAttribute("size");

            // 解析是否只读，默认为 false（可读写）
            // 注意：readOnly=false 时，缓存对象会被序列化/反序列化
            boolean readWrite = !context.getBooleanAttribute("readOnly", false);
            // 解析是否阻塞，默认为 false（非阻塞）
            // blocking=true 时，获取缓存时会加锁，防止缓存击穿
            boolean blocking = context.getBooleanAttribute("blocking", false);

            // 解析子元素中的自定义属性，用于传递给缓存实现
            Properties props = context.getChildrenAsProperties();

            // 使用构建助手创建新的缓存实例，并应用到当前命名空间
            builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
        }
    }

    private void parameterMapElement(List<XNode> list) throws Exception {
        for (XNode parameterMapNode : list) {
            String id = parameterMapNode.getStringAttribute("id");
            String type = parameterMapNode.getStringAttribute("type");
            Class<?> parameterClass = resolveClass(type);
            List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
            List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
            for (XNode parameterNode : parameterNodes) {
                String property = parameterNode.getStringAttribute("property");
                String javaType = parameterNode.getStringAttribute("javaType");
                String jdbcType = parameterNode.getStringAttribute("jdbcType");
                String resultMap = parameterNode.getStringAttribute("resultMap");
                String mode = parameterNode.getStringAttribute("mode");
                String typeHandler = parameterNode.getStringAttribute("typeHandler");
                Integer numericScale = parameterNode.getIntAttribute("numericScale");
                ParameterMode modeEnum = resolveParameterMode(mode);
                Class<?> javaTypeClass = resolveClass(javaType);
                JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
                @SuppressWarnings("unchecked")
                Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
                ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
                parameterMappings.add(parameterMapping);
            }
            builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
        }
    }

    private void resultMapElements(List<XNode> list) throws Exception {
        for (XNode resultMapNode : list) {
            try {
                resultMapElement(resultMapNode);
            } catch (IncompleteElementException e) {
                // ignore, it will be retried
            }
        }
    }

    private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
        return resultMapElement(resultMapNode, Collections.<ResultMapping>emptyList());
    }

    private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings) throws Exception {
        ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
        String id = resultMapNode.getStringAttribute("id",
                resultMapNode.getValueBasedIdentifier());
        String type = resultMapNode.getStringAttribute("type",
                resultMapNode.getStringAttribute("ofType",
                        resultMapNode.getStringAttribute("resultType",
                                resultMapNode.getStringAttribute("javaType"))));
        String extend = resultMapNode.getStringAttribute("extends");
        Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
        Class<?> typeClass = resolveClass(type);
        Discriminator discriminator = null;
        List<ResultMapping> resultMappings = new ArrayList<ResultMapping>();
        resultMappings.addAll(additionalResultMappings);
        List<XNode> resultChildren = resultMapNode.getChildren();
        for (XNode resultChild : resultChildren) {
            if ("constructor".equals(resultChild.getName())) {
                processConstructorElement(resultChild, typeClass, resultMappings);
            } else if ("discriminator".equals(resultChild.getName())) {
                discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
            } else {
                List<ResultFlag> flags = new ArrayList<ResultFlag>();
                if ("id".equals(resultChild.getName())) {
                    flags.add(ResultFlag.ID);
                }
                resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
            }
        }
        ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
        try {
            return resultMapResolver.resolve();
        } catch (IncompleteElementException e) {
            configuration.addIncompleteResultMap(resultMapResolver);
            throw e;
        }
    }

    private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
        List<XNode> argChildren = resultChild.getChildren();
        for (XNode argChild : argChildren) {
            List<ResultFlag> flags = new ArrayList<ResultFlag>();
            flags.add(ResultFlag.CONSTRUCTOR);
            if ("idArg".equals(argChild.getName())) {
                flags.add(ResultFlag.ID);
            }
            resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
        }
    }

    private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
        String column = context.getStringAttribute("column");
        String javaType = context.getStringAttribute("javaType");
        String jdbcType = context.getStringAttribute("jdbcType");
        String typeHandler = context.getStringAttribute("typeHandler");
        Class<?> javaTypeClass = resolveClass(javaType);
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        Map<String, String> discriminatorMap = new HashMap<String, String>();
        for (XNode caseChild : context.getChildren()) {
            String value = caseChild.getStringAttribute("value");
            String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings));
            discriminatorMap.put(value, resultMap);
        }
        return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
    }

    private void sqlElement(List<XNode> list) throws Exception {
        if (configuration.getDatabaseId() != null) {
            sqlElement(list, configuration.getDatabaseId());
        }
        sqlElement(list, null);
    }

    private void sqlElement(List<XNode> list, String requiredDatabaseId) throws Exception {
        for (XNode context : list) {
            String databaseId = context.getStringAttribute("databaseId");
            String id = context.getStringAttribute("id");
            id = builderAssistant.applyCurrentNamespace(id, false);
            if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
                sqlFragments.put(id, context);
            }
        }
    }

    private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
        if (requiredDatabaseId != null) {
            if (!requiredDatabaseId.equals(databaseId)) {
                return false;
            }
        } else {
            if (databaseId != null) {
                return false;
            }
            // skip this fragment if there is a previous one with a not null databaseId
            if (this.sqlFragments.containsKey(id)) {
                XNode context = this.sqlFragments.get(id);
                if (context.getStringAttribute("databaseId") != null) {
                    return false;
                }
            }
        }
        return true;
    }

    private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) throws Exception {
        String property;
        if (flags.contains(ResultFlag.CONSTRUCTOR)) {
            property = context.getStringAttribute("name");
        } else {
            property = context.getStringAttribute("property");
        }
        String column = context.getStringAttribute("column");
        String javaType = context.getStringAttribute("javaType");
        String jdbcType = context.getStringAttribute("jdbcType");
        String nestedSelect = context.getStringAttribute("select");
        String nestedResultMap = context.getStringAttribute("resultMap",
                processNestedResultMappings(context, Collections.<ResultMapping>emptyList()));
        String notNullColumn = context.getStringAttribute("notNullColumn");
        String columnPrefix = context.getStringAttribute("columnPrefix");
        String typeHandler = context.getStringAttribute("typeHandler");
        String resultSet = context.getStringAttribute("resultSet");
        String foreignColumn = context.getStringAttribute("foreignColumn");
        boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
        Class<?> javaTypeClass = resolveClass(javaType);
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
    }

    private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings) throws Exception {
        if ("association".equals(context.getName())
                || "collection".equals(context.getName())
                || "case".equals(context.getName())) {
            if (context.getStringAttribute("select") == null) {
                ResultMap resultMap = resultMapElement(context, resultMappings);
                return resultMap.getId();
            }
        }
        return null;
    }

    private void bindMapperForNamespace() {
        String namespace = builderAssistant.getCurrentNamespace();
        if (namespace != null) {
            Class<?> boundType = null;
            try {
                boundType = Resources.classForName(namespace);
            } catch (ClassNotFoundException e) {
                //ignore, bound type is not required
            }
            if (boundType != null) {
                if (!configuration.hasMapper(boundType)) {
                    // Spring may not know the real resource name so we set a flag
                    // to prevent loading again this resource from the mapper interface
                    // look at MapperAnnotationBuilder#loadXmlResource
                    configuration.addLoadedResource("namespace:" + namespace);
                    configuration.addMapper(boundType);
                }
            }
        }
    }

}