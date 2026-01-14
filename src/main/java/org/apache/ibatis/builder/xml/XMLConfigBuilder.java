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
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

    private boolean parsed;
    private final XPathParser parser;
    private String environment;
    private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

    public XMLConfigBuilder(Reader reader) {
        this(reader, null, null);
    }

    public XMLConfigBuilder(Reader reader, String environment) {
        this(reader, environment, null);
    }

    public XMLConfigBuilder(Reader reader, String environment, Properties props) {
        this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    public XMLConfigBuilder(InputStream inputStream) {
        this(inputStream, null, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment) {
        this(inputStream, environment, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
        this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
        super(new Configuration());
        ErrorContext.instance().resource("SQL Mapper Configuration");
        this.configuration.setVariables(props);
        this.parsed = false;
        this.environment = environment;
        this.parser = parser;
    }

    public Configuration parse() {
        if (parsed) {
            throw new BuilderException("Each XMLConfigBuilder can only be used once.");
        }
        parsed = true;
        // 开始解析
        parseConfiguration(parser.evalNode("/configuration"));
        return configuration;
    }

    /**
     * 解析 configuration 节点
     * 按照 MyBatis 配置文件的规范顺序解析各个配置元素
     *
     * @param root configuration 节点的 XNode 对象
     * @throws BuilderException 如果解析过程中出现异常
     */
    private void parseConfiguration(XNode root) {
        try {
            //issue #117 read properties first
            // 解析 properties 节点 - 必须最先解析，因为其他配置可能引用属性值
            propertiesElement(root.evalNode("properties"));

            // 解析 settings 节点并转换为 Properties 对象
            Properties settings = settingsAsProperties(root.evalNode("settings"));

            // 加载自定义 VFS (Virtual File System) 实现
            loadCustomVfs(settings);

            // 解析类型别名配置
            typeAliasesElement(root.evalNode("typeAliases"));

            // 解析插件配置
            pluginElement(root.evalNode("plugins"));

            // 解析对象工厂配置
            objectFactoryElement(root.evalNode("objectFactory"));

            // 解析对象包装器工厂配置
            objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));

            // 解析反射器工厂配置
            reflectorFactoryElement(root.evalNode("reflectorFactory"));

            // 应用 settings 配置到 Configuration 对象
            settingsElement(settings);

            // read it after objectFactory and objectWrapperFactory issue #631
            // 解析环境配置（数据源、事务管理器等）
            environmentsElement(root.evalNode("environments"));

            // 解析数据库标识提供者配置
            databaseIdProviderElement(root.evalNode("databaseIdProvider"));

            // 解析类型处理器配置
            typeHandlerElement(root.evalNode("typeHandlers"));

            // 解析映射器配置（最后解析，因为可能依赖前面的配置）
            mapperElement(root.evalNode("mappers"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
        }
    }


    private Properties settingsAsProperties(XNode context) {
        if (context == null) {
            return new Properties();
        }
        Properties props = context.getChildrenAsProperties();
        // Check that all settings are known to the configuration class
        MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
        for (Object key : props.keySet()) {
            if (!metaConfig.hasSetter(String.valueOf(key))) {
                throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
            }
        }
        return props;
    }

    private void loadCustomVfs(Properties props) throws ClassNotFoundException {
        String value = props.getProperty("vfsImpl");
        if (value != null) {
            String[] clazzes = value.split(",");
            for (String clazz : clazzes) {
                if (!clazz.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Class<? extends VFS> vfsImpl = (Class<? extends VFS>) Resources.classForName(clazz);
                    configuration.setVfsImpl(vfsImpl);
                }
            }
        }
    }

    private void typeAliasesElement(XNode parent) {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                if ("package".equals(child.getName())) {
                    String typeAliasPackage = child.getStringAttribute("name");
                    configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
                } else {
                    String alias = child.getStringAttribute("alias");
                    String type = child.getStringAttribute("type");
                    try {
                        Class<?> clazz = Resources.classForName(type);
                        if (alias == null) {
                            typeAliasRegistry.registerAlias(clazz);
                        } else {
                            typeAliasRegistry.registerAlias(alias, clazz);
                        }
                    } catch (ClassNotFoundException e) {
                        throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
                    }
                }
            }
        }
    }

    private void pluginElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                String interceptor = child.getStringAttribute("interceptor");
                Properties properties = child.getChildrenAsProperties();
                Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
                interceptorInstance.setProperties(properties);
                configuration.addInterceptor(interceptorInstance);
            }
        }
    }

    private void objectFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            Properties properties = context.getChildrenAsProperties();
            ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
            factory.setProperties(properties);
            configuration.setObjectFactory(factory);
        }
    }

    private void objectWrapperFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
            configuration.setObjectWrapperFactory(factory);
        }
    }

    private void reflectorFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
            configuration.setReflectorFactory(factory);
        }
    }

    /**
     * 解析 properties 配置元素
     * 支持三种方式加载属性：内联属性、资源文件、URL 文件
     * 按照优先级合并属性：内联属性 < 外部文件属性 < 程序传入属性
     *
     * @param context properties 节点的 XNode 对象
     * @throws BuilderException 如果同时指定了 resource 和 url 属性，或者加载属性文件失败
     */
    private void propertiesElement(XNode context) throws Exception {
        if (context != null) {
            // 获取内联定义的属性（在 properties 标签内直接定义的属性）
            Properties defaults = context.getChildrenAsProperties();

            // 获取外部属性文件的资源路径和 URL 路径
            String resource = context.getStringAttribute("resource");
            String url = context.getStringAttribute("url");

            // 检查是否同时指定了 resource 和 url（不允许同时指定）
            if (resource != null && url != null) {
                throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
            }

            // 加载资源文件中的属性并合并到 defaults 中
            if (resource != null) {
                defaults.putAll(Resources.getResourceAsProperties(resource));
            }
            // 加载 URL 文件中的属性并合并到 defaults 中
            else if (url != null) {
                defaults.putAll(Resources.getUrlAsProperties(url));
            }

            // 获取程序运行时传入的属性（最高优先级）
            Properties vars = configuration.getVariables();
            if (vars != null) {
                defaults.putAll(vars);
            }

            // 将最终的属性设置到解析器和配置对象中
            parser.setVariables(defaults);
            configuration.setVariables(defaults);
        }
    }

    private void settingsElement(Properties props) throws Exception {
        configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
        configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
        configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
        configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
        configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
        configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
        configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
        configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
        configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
        configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
        configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
        configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
        configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
        configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
        configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
        configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
        configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
        configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
        configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler> typeHandler = (Class<? extends TypeHandler>) resolveClass(props.getProperty("defaultEnumTypeHandler"));
        configuration.setDefaultEnumTypeHandler(typeHandler);
        configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
        configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
        configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
        configuration.setLogPrefix(props.getProperty("logPrefix"));
        @SuppressWarnings("unchecked")
        Class<? extends Log> logImpl = (Class<? extends Log>) resolveClass(props.getProperty("logImpl"));
        configuration.setLogImpl(logImpl);
        configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    }

    private void environmentsElement(XNode context) throws Exception {
        if (context != null) {
            if (environment == null) {
                environment = context.getStringAttribute("default");
            }
            for (XNode child : context.getChildren()) {
                String id = child.getStringAttribute("id");
                if (isSpecifiedEnvironment(id)) {
                    TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
                    DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
                    DataSource dataSource = dsFactory.getDataSource();
                    Environment.Builder environmentBuilder = new Environment.Builder(id)
                            .transactionFactory(txFactory)
                            .dataSource(dataSource);
                    configuration.setEnvironment(environmentBuilder.build());
                }
            }
        }
    }

    private void databaseIdProviderElement(XNode context) throws Exception {
        DatabaseIdProvider databaseIdProvider = null;
        if (context != null) {
            String type = context.getStringAttribute("type");
            // awful patch to keep backward compatibility
            if ("VENDOR".equals(type)) {
                type = "DB_VENDOR";
            }
            Properties properties = context.getChildrenAsProperties();
            databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
            databaseIdProvider.setProperties(properties);
        }
        Environment environment = configuration.getEnvironment();
        if (environment != null && databaseIdProvider != null) {
            String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
            configuration.setDatabaseId(databaseId);
        }
    }

    private TransactionFactory transactionManagerElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            Properties props = context.getChildrenAsProperties();
            TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a TransactionFactory.");
    }

    private DataSourceFactory dataSourceElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            Properties props = context.getChildrenAsProperties();
            DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a DataSourceFactory.");
    }

    private void typeHandlerElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                if ("package".equals(child.getName())) {
                    String typeHandlerPackage = child.getStringAttribute("name");
                    typeHandlerRegistry.register(typeHandlerPackage);
                } else {
                    String javaTypeName = child.getStringAttribute("javaType");
                    String jdbcTypeName = child.getStringAttribute("jdbcType");
                    String handlerTypeName = child.getStringAttribute("handler");
                    Class<?> javaTypeClass = resolveClass(javaTypeName);
                    JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
                    Class<?> typeHandlerClass = resolveClass(handlerTypeName);
                    if (javaTypeClass != null) {
                        if (jdbcType == null) {
                            typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
                        } else {
                            typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
                        }
                    } else {
                        typeHandlerRegistry.register(typeHandlerClass);
                    }
                }
            }
        }
    }

    /**
     * 解析配置文件中的 mappers 元素，注册映射器
     * 
     * 该方法负责处理 MyBatis 配置文件中的 <mappers> 配置，支持四种方式注册映射器：
     * 1. 通过 package 批量注册指定包下的所有 Mapper 接口
     * 2. 通过 resource 指定 XML 映射文件路径注册映射器
     * 3. 通过 url 指定 XML 映射文件 URL 路径注册映射器
     * 4. 通过 class 指定 Mapper 接口类名注册映射器
     * 
     * @param parent mappers 节点的父节点，包含所有 mapper 子节点
     * @throws Exception 如果解析过程中发生异常，如资源找不到、类加载失败等
     * 
     * 执行流程：
     * 1. 遍历 mappers 节点下的所有子节点
     * 2. 对于 package 节点，扫描指定包下的所有 Mapper 接口进行注册
     * 3. 对于 mapper 节点，根据配置的资源类型进行不同处理：
     *    - resource：从类路径加载 XML 映射文件并解析
     *    - url：从 URL 加载 XML 映射文件并解析
     *    - class：加载指定的 Mapper 接口类并注册
     * 4. 验证配置合法性，确保只指定一种资源类型
     * 
     * 设计说明：
     * - 使用策略模式处理不同类型的映射器注册方式
     * - XML 映射文件通过 XMLMapperBuilder 解析，支持完整的映射配置
     * - 接口方式注册只适用于注解驱动的映射器
     * - 错误上下文设置有助于精确定位配置问题
     * 
     * 配置示例：
     * <mappers>
     *   <package name="com.example.mapper"/>
     *   <mapper resource="mapper/UserMapper.xml"/>
     *   <mapper url="file:///var/mappers/OrderMapper.xml"/>
     *   <mapper class="com.example.mapper.ProductMapper"/>
     * </mappers>
     */
    private void mapperElement(XNode parent) throws Exception {
        if (parent != null) {
            // 遍历 mappers 节点下的所有子节点
            for (XNode child : parent.getChildren()) {
                // 处理 package 方式注册映射器（批量注册）
                if ("package".equals(child.getName())) {
                    // 获取包名
                    String mapperPackage = child.getStringAttribute("name");
                    // 扫描包下面的所有Mapper接口进行注册
                    configuration.addMappers(mapperPackage);
                } else {
                    // 处理单个 mapper 节点，支持三种资源类型
                    String resource = child.getStringAttribute("resource");
                    String url = child.getStringAttribute("url");
                    String mapperClass = child.getStringAttribute("class");
                    
                    // 处理 resource 方式：从类路径加载 XML 映射文件
                    if (resource != null && url == null && mapperClass == null) {
                        // 设置错误上下文，便于定位问题
                        ErrorContext.instance().resource(resource);
                        // 从类路径加载 XML 映射文件
                        InputStream inputStream = Resources.getResourceAsStream(resource);
                        // 创建 XML 映射文件构建器并解析
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
                        mapperParser.parse();
                    } 
                    // 处理 url 方式：从 URL 加载 XML 映射文件
                    else if (resource == null && url != null && mapperClass == null) {
                        // 设置错误上下文，便于定位问题
                        ErrorContext.instance().resource(url);
                        // 从 URL 加载 XML 映射文件
                        InputStream inputStream = Resources.getUrlAsStream(url);
                        // 创建 XML 映射文件构建器并解析
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
                        mapperParser.parse();
                    } 
                    // 处理 class 方式：直接注册 Mapper 接口类
                    else if (resource == null && url == null && mapperClass != null) {
                        // 加载 Mapper 接口类
                        Class<?> mapperInterface = Resources.classForName(mapperClass);
                        // 注册 Mapper 接口
                        configuration.addMapper(mapperInterface);
                    } 
                    // 配置错误：同时指定了多种资源类型
                    else {
                        throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
                    }
                }
            }
        }
    }

    private boolean isSpecifiedEnvironment(String id) {
        if (environment == null) {
            throw new BuilderException("No environment specified.");
        } else if (id == null) {
            throw new BuilderException("Environment requires an id attribute.");
        } else if (environment.equals(id)) {
            return true;
        }
        return false;
    }

}