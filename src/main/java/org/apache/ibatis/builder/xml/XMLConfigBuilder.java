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

    /**
     * 解析MyBatis配置文件，构建Configuration对象
     * <p>
     * 该方法是XMLConfigBuilder的核心方法，负责解析XML配置文件并构建完整的Configuration对象
     * 每个XMLConfigBuilder实例只能使用一次，防止重复解析导致的状态不一致
     *
     * @return 构建完成的Configuration对象，包含MyBatis的所有配置信息
     * @throws BuilderException 如果已经解析过或者解析过程中出现错误
     */
    public Configuration parse() {
        // 检查是否已经解析过，确保每个XMLConfigBuilder实例只能使用一次
        if (parsed) {
            throw new BuilderException("Each XMLConfigBuilder can only be used once.");
        }
        // 标记已解析状态，防止重复解析
        parsed = true;
        // 开始解析
        // 使用XPathParser获取configuration节点并解析其所有子元素
        parseConfiguration(parser.evalNode("/configuration"));
        // 返回构建完成的Configuration对象
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
            // 使用XPath表达式获取properties节点并解析，将外部属性文件和内联属性加载到配置中
            propertiesElement(root.evalNode("properties"));

            // 解析 settings 节点并转换为 Properties 对象
            // 获取settings节点并将其内容转换为Properties对象，用于后续配置
            Properties settings = settingsAsProperties(root.evalNode("settings"));

            // 加载自定义 VFS (Virtual File System) 实现
            // 根据settings中的vfsImpl配置加载自定义虚拟文件系统实现
            loadCustomVfs(settings);

            // 解析类型别名配置
            // 解析typeAliases节点，注册Java类型到别名的映射，简化XML中的类型引用
            typeAliasesElement(root.evalNode("typeAliases"));

            // 解析插件配置
            // 解析plugins节点，实例化并注册拦截器插件，用于扩展MyBatis功能
            pluginElement(root.evalNode("plugins"));

            // 解析对象工厂配置
            // 解析objectFactory节点，设置自定义对象工厂，用于创建结果对象实例
            objectFactoryElement(root.evalNode("objectFactory"));

            // 解析对象包装器工厂配置
            // 解析objectWrapperFactory节点，设置对象包装器工厂，用于创建对象元数据包装器
            objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));

            // 解析反射器工厂配置
            // 解析reflectorFactory节点，设置反射器工厂，用于创建类的反射信息
            reflectorFactoryElement(root.evalNode("reflectorFactory"));

            // 应用 settings 配置到 Configuration 对象
            // 将之前解析的settings属性应用到Configuration对象中，设置各种行为开关和默认值
            settingsElement(settings);

            // read it after objectFactory and objectWrapperFactory issue #631
            // 解析环境配置（数据源、事务管理器等）
            // 解析environments节点，配置数据源和事务管理器，确定使用哪个环境配置
            environmentsElement(root.evalNode("environments"));

            // 解析数据库标识提供者配置
            // 解析databaseIdProvider节点，设置数据库产品标识，用于支持多数据库
            databaseIdProviderElement(root.evalNode("databaseIdProvider"));

            // 解析类型处理器配置
            // 解析typeHandlers节点，注册Java类型与JDBC类型的转换处理器
            typeHandlerElement(root.evalNode("typeHandlers"));

            // 解析映射器配置（最后解析，因为可能依赖前面的配置）
            // 解析mappers节点，加载SQL映射文件或映射接口，是MyBatis的核心配置部分
            mapperElement(root.evalNode("mappers"));
        } catch (Exception e) {
            // 捕获所有异常并包装成BuilderException，提供统一的错误信息格式
            throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
        }
    }


    /**
     * 将 settings 节点转换为 Properties 对象
     * 验证所有设置项是否在 Configuration 类中有对应的 setter 方法
     * xml 配置示例：
     * <pre>
     * &lt;settings&gt;
     *   &lt;setting name="cacheEnabled" value="true"/&gt;
     *   &lt;setting name="lazyLoadingEnabled" value="false"/&gt;
     *   &lt;setting name="multipleResultSetsEnabled" value="true"/&gt;
     *   &lt;setting name="useColumnLabel" value="true"/&gt;
     *   &lt;setting name="useGeneratedKeys" value="false"/&gt;
     *   &lt;setting name="autoMappingBehavior" value="PARTIAL"/&gt;
     *   &lt;setting name="autoMappingUnknownColumnBehavior" value="WARNING"/&gt;
     *   &lt;setting name="defaultExecutorType" value="SIMPLE"/&gt;
     *   &lt;setting name="defaultStatementTimeout" value="25"/&gt;
     *   &lt;setting name="defaultFetchSize" value="100"/&gt;
     *   &lt;setting name="safeRowBoundsEnabled" value="false"/&gt;
     *   &lt;setting name="defaultScriptingLanguage" value="org.apache.ibatis.scripting.xmltags.XMLLanguageDriver"/&gt;
     *   &lt;setting name="mapUnderscoreToCamelCase" value="true"/&gt;
     *   &lt;setting name="localCacheScope" value="SESSION"/&gt;
     *   &lt;setting name="jdbcTypeForNull" value="NULL"/&gt;
     *   &lt;setting name="lazyLoadTriggerMethods" value="equals,clone,hashCode,toString"/&gt;
     * &lt;/settings&gt;
     * </pre>
     *
     * @param context settings 节点的 XNode 对象
     * @return 包含所有设置项的 Properties 对象
     * @throws BuilderException 当设置项在 Configuration 类中不存在时抛出异常
     */
    private Properties settingsAsProperties(XNode context) {
        // 检查 context 是否为 null
        if (context == null) {
            // 如果为 null，返回空的 Properties 对象
            return new Properties();
        }
        // 将 context 的所有子节点转换为 Properties 对象
        Properties props = context.getChildrenAsProperties();

        // Check that all settings are known to the configuration class
        // 创建 Configuration 类的 MetaClass 对象，用于检查属性是否存在
        MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
        // 遍历所有设置项
        for (Object key : props.keySet()) {
            // 检查 Configuration 类是否有对应的 setter 方法
            if (!metaConfig.hasSetter(String.valueOf(key))) {
                // 如果没有对应的 setter 方法，抛出 BuilderException 异常
                throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
            }
        }
        // 返回验证通过的 Properties 对象
        return props;
    }

    /**
     * 加载自定义虚拟文件系统(VFS)实现
     * 从配置属性中获取自定义VFS实现类，并设置到Configuration中
     * 支持多个VFS实现类，以逗号分隔
     * xml 配置示例：
     * <pre>
     * &lt;settings&gt;
     *   &lt;setting name="vfsImpl" value="com.example.CustomVFS,com.example.AnotherCustomVFS"/&gt;
     * &lt;/settings&gt;
     * </pre>
     *
     * @param props 包含配置信息的Properties对象
     * @throws ClassNotFoundException 当指定的VFS实现类不存在时抛出异常
     */
    private void loadCustomVfs(Properties props) throws ClassNotFoundException {
        // 从属性中获取vfsImpl配置项的值
        String value = props.getProperty("vfsImpl");
        // 检查vfsImpl配置项是否存在
        if (value != null) {
            // 使用逗号分割多个VFS实现类名
            String[] clazzes = value.split(",");
            // 遍历每个VFS实现类名
            for (String clazz : clazzes) {
                // 检查类名是否为空
                if (!clazz.isEmpty()) {
                    // 抑制未检查类型转换的警告
                    @SuppressWarnings("unchecked")
                    // 通过类名加载VFS实现类
                    Class<? extends VFS> vfsImpl = (Class<? extends VFS>) Resources.classForName(clazz);
                    // 将VFS实现类设置到Configuration中
                    configuration.setVfsImpl(vfsImpl);
                }
            }
        }
    }

    /**
     * 解析 typeAliases 配置元素
     * 支持两种方式注册类型别名：通过包名扫描注册和通过类名注册
     * 类型别名用于简化XML配置文件中的类型引用，提高可读性
     * xml 配置示例：
     * <pre>
     * &lt;typeAliases&gt;
     *   &lt;package name="com.example.model"/&gt;
     *   &lt;typeAlias alias="User" type="com.example.model.User"/&gt;
     * &lt;/typeAliases&gt;
     * </pre>
     *
     * @param parent typeAliases 节点的 XNode 对象
     */
    private void typeAliasesElement(XNode parent) {
        // 检查 parent 节点是否存在
        if (parent != null) {
            // 遍历 typeAliases 节点的所有子节点
            for (XNode child : parent.getChildren()) {
                // 检查子节点是否为 package 配置（批量注册方式）
                if ("package".equals(child.getName())) {
                    // 获取 package 节点的 name 属性值（包名）
                    String typeAliasPackage = child.getStringAttribute("name");
                    // 注册指定包下的所有类的类型别名
                    configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
                } else {
                    // 单个类型别名注册方式
                    // 获取 alias 节点的 alias 属性值（别名）
                    String alias = child.getStringAttribute("alias");
                    // 获取 alias 节点的 type 属性值（类全限定名）
                    String type = child.getStringAttribute("type");
                    try {
                        // 根据类全限定名加载对应的 Class 对象
                        Class<?> clazz = Resources.classForName(type);
                        // 检查是否指定了别名
                        if (alias == null) {
                            // 如果没有指定别名，使用类名的小写形式作为别名注册
                            typeAliasRegistry.registerAlias(clazz);
                        } else {
                            // 如果指定了别名，使用指定的别名注册
                            typeAliasRegistry.registerAlias(alias, clazz);
                        }
                    } catch (ClassNotFoundException e) {
                        // 捕获类未找到异常并包装成 BuilderException
                        throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
                    }
                }
            }
        }
    }

    /**
     * 解析plugins配置元素
     * 用于配置MyBatis插件拦截器，支持配置多个拦截器
     * 每个拦截器可以设置自定义属性，用于扩展MyBatis的功能
     * xml 配置文件示例：
     * <pre>
     * &lt;plugins&gt;
     *   &lt;plugin interceptor="com.example.ExampleInterceptor"&gt;
     *     &lt;property name="property1" value="value1"/&gt;
     *     &lt;property name="property2" value="value2"/&gt;
     *   &lt;/plugin&gt;
     * &lt;/plugins&gt;
     * </pre>
     *
     * @param parent plugins 节点的 XNode 对象
     * @throws Exception 当拦截器类无法加载或实例化时抛出异常
     */
    private void pluginElement(XNode parent) throws Exception {
        // 检查父节点是否为null
        if (parent != null) {
            // 遍历父节点下的所有子节点（每个子节点代表一个插件配置）
            for (XNode child : parent.getChildren()) {
                // 获取子节点中的interceptor属性值，即拦截器的全限定类名
                String interceptor = child.getStringAttribute("interceptor");
                // 获取子节点下的所有property子节点，并将其转换为Properties对象
                Properties properties = child.getChildrenAsProperties();
                // 根据拦截器类名解析类对象，并创建实例
                Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
                // 设置拦截器的属性
                interceptorInstance.setProperties(properties);
                // 将拦截器实例添加到配置对象中
                configuration.addInterceptor(interceptorInstance);
            }
        }
    }

    /**
     * 解析objectFactory配置元素
     * 用于配置MyBatis的对象工厂，该工厂负责创建结果对象实例
     * 默认情况下，MyBatis使用DefaultObjectFactory，但可以通过此配置自定义对象创建逻辑
     * xml 配置文件示例：
     * <pre>
     * &lt;objectFactory type="com.example.CustomObjectFactory"&gt;
     *   &lt;property name="property1" value="value1"/&gt;
     *   &lt;property name="property2" value="value2"/&gt;
     * &lt;/objectFactory&gt;
     * </pre>
     *
     * @param context objectFactory 节点的 XNode 对象
     * @throws Exception 当对象工厂类无法加载或实例化时抛出异常
     */
    private void objectFactoryElement(XNode context) throws Exception {
        // 检查上下文节点是否为null
        if (context != null) {
            // 获取objectFactory节点中的type属性值，即对象工厂的全限定类名
            String type = context.getStringAttribute("type");
            // 获取objectFactory节点下的所有property子节点，并将其转换为Properties对象
            Properties properties = context.getChildrenAsProperties();
            // 根据类型名称解析类对象，并创建对象工厂实例
            ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
            // 设置对象工厂的属性
            factory.setProperties(properties);
            // 将对象工厂实例设置到配置对象中
            configuration.setObjectFactory(factory);
        }
    }

    /**
     * 解析objectWrapperFactory配置元素
     * 用于配置MyBatis的对象包装器工厂，该工厂负责创建对象包装器
     * 对象包装器用于包装结果对象，提供对对象属性的访问、元数据信息等功能
     * 默认情况下，MyBatis使用DefaultObjectWrapperFactory，但可以通过此配置自定义对象包装逻辑
     * xml 配置文件示例：
     * <pre>
     * &lt;objectWrapperFactory type="com.example.CustomObjectWrapperFactory"/&gt;
     * </pre>
     *
     * @param context objectWrapperFactory 节点的 XNode 对象
     * @throws Exception 当对象包装器工厂类无法加载或实例化时抛出异常
     */
    private void objectWrapperFactoryElement(XNode context) throws Exception {
        // 检查上下文节点是否为null
        if (context != null) {
            // 获取objectWrapperFactory节点中的type属性值，即对象包装器工厂的全限定类名
            String type = context.getStringAttribute("type");
            // 根据类型名称解析类对象，并创建对象包装器工厂实例
            ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
            // 将对象包装器工厂实例设置到配置对象中
            configuration.setObjectWrapperFactory(factory);
        }
    }

    /**
     * 解析reflectorFactory配置元素
     * 用于配置MyBatis的反射器工厂，该工厂负责创建反射器对象
     * 反射器用于缓存类的元数据信息，如属性、方法等，提高反射性能
     * 默认情况下，MyBatis使用DefaultReflectorFactory，但可以通过此配置自定义反射逻辑
     * xml 配置文件示例：
     * <pre>
     * &lt;reflectorFactory type="com.example.CustomReflectorFactory"/&gt;
     * </pre>
     *
     * @param context reflectorFactory 节点的 XNode 对象
     * @throws Exception 当反射器工厂类无法加载或实例化时抛出异常
     */
    private void reflectorFactoryElement(XNode context) throws Exception {
        // 检查上下文节点是否为null
        if (context != null) {
            // 获取reflectorFactory节点中的type属性值，即反射器工厂的全限定类名
            String type = context.getStringAttribute("type");
            // 根据类型名称解析类对象，并创建反射器工厂实例
            ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
            // 将反射器工厂实例设置到配置对象中
            configuration.setReflectorFactory(factory);
        }
    }


    /**
     * 解析 properties 配置元素
     * 支持三种方式加载属性：内联属性、资源文件、URL 文件
     * 按照优先级合并属性：内联属性 < 外部文件属性 < 程序传入属性
     * xml 配置文件示例：
     * <pre>
     * &lt;properties resource="db.properties" url="http://example.com/db.properties"&gt;
     *   &lt;property name="username" value="root"/&gt;
     *   &lt;property name="password" value="123456"/&gt;
     * &lt;/properties&gt;
     * </pre>
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

    /**
     * 解析settings配置元素
     * 用于设置MyBatis的全局配置参数，这些参数会影响MyBatis的运行时行为
     * 每个设置项都有默认值，如果未在配置中指定，则使用默认值
     * xml 配置文件示例：
     * <pre>
     * &lt;settings&gt;
     *   &lt;setting name="cacheEnabled" value="true"/&gt;
     *   &lt;setting name="lazyLoadingEnabled" value="false"/&gt;
     *   &lt;setting name="multipleResultSetsEnabled" value="true"/&gt;
     *   &lt;setting name="useColumnLabel" value="true"/&gt;
     *   &lt;setting name="useGeneratedKeys" value="false"/&gt;
     *   &lt;setting name="autoMappingBehavior" value="PARTIAL"/&gt;
     *   &lt;setting name="defaultExecutorType" value="SIMPLE"/&gt;
     *   &lt;setting name="defaultStatementTimeout" value="25"/&gt;
     *   &lt;setting name="defaultFetchSize" value="100"/&gt;
     *   &lt;setting name="safeRowBoundsEnabled" value="false"/&gt;
     *   &lt;setting name="mapUnderscoreToCamelCase" value="false"/&gt;
     *   &lt;setting name="localCacheScope" value="SESSION"/&gt;
     *   &lt;setting name="jdbcTypeForNull" value="OTHER"/&gt;
     *   &lt;setting name="lazyLoadTriggerMethods" value="equals,clone,hashCode,toString"/&gt;
     * &lt;/settings&gt;
     * </pre>
     *
     * @param props 包含所有设置项的Properties对象
     * @throws Exception 当设置项无法解析时抛出异常
     */
    private void settingsElement(Properties props) throws Exception {
        // 设置自动映射行为，默认为PARTIAL（部分映射）
        configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
        // 设置未知列的自动映射行为，默认为NONE（不映射）
        configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
        // 设置是否启用缓存，默认为true（启用）
        configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
        // 设置代理工厂，用于创建代理对象
        configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
        // 设置是否启用延迟加载，默认为false（不启用）
        configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
        // 设置是否启用激进延迟加载，默认为false（不启用）
        configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
        // 设置是否允许多个结果集，默认为true（允许）
        configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
        // 设置是否使用列标签而非列名，默认为true（使用）
        configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
        // 设置是否允许JDBC生成主键，默认为false（不允许）
        configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
        // 设置默认执行器类型，默认为SIMPLE（简单执行器）
        configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
        // 设置默认语句超时时间（秒），默认为null（不设置）
        configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
        // 设置默认获取大小，默认为null（不设置）
        configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
        // 设置是否开启下划线到驼峰命名映射，默认为false（不开启）
        configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
        // 设置是否启用安全的行边界，默认为false（不启用）
        configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
        // 设置本地缓存范围，默认为SESSION（会话级别）
        configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
        // 设置当值为null时的JDBC类型，默认为OTHER
        configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
        // 设置触发延迟加载的方法，默认为equals,clone,hashCode,toString
        configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
        // 设置是否启用安全的结果处理器，默认为true（启用）
        configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
        // 设置默认脚本语言，用于动态SQL
        configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
        // 设置枚举类型的默认类型处理器
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler> typeHandler = (Class<? extends TypeHandler>) resolveClass(props.getProperty("defaultEnumTypeHandler"));
        configuration.setDefaultEnumTypeHandler(typeHandler);
        // 设置当值为null时是否调用setter方法，默认为false（不调用）
        configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
        // 设置是否使用实际的参数名，默认为true（使用）
        configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
        // 设置当结果集为空行时是否返回实例，默认为false（不返回）
        configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
        // 设置日志前缀
        configuration.setLogPrefix(props.getProperty("logPrefix"));
        // 设置日志实现类
        @SuppressWarnings("unchecked")
        Class<? extends Log> logImpl = (Class<? extends Log>) resolveClass(props.getProperty("logImpl"));
        configuration.setLogImpl(logImpl);
        // 设置配置工厂
        configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    }

    /**
     * 解析配置文件中的environments元素，配置MyBatis运行环境
     * <p>
     * 该方法负责处理MyBatis配置文件中的<environments>配置，支持多环境配置，
     * 可以根据需要选择不同的环境（如开发、测试、生产环境），每个环境包含
     * 事务管理器和数据源配置。
     * <pre>
     * 执行流程：
     * 1. 检查context节点是否为null
     * 2. 如果environment为null，获取default属性作为默认环境
     * 3. 遍历所有environment子节点
     * 4. 对于每个environment，获取其id属性
     * 5. 检查当前environment是否是指定的环境
     * 6. 解析事务管理器配置
     * 7. 解析数据源配置
     * 8. 构建Environment对象并设置到Configuration中
     * <p>
     * 设计说明：
     * - 支持多环境配置，便于不同环境间的切换
     * - 使用建造者模式创建Environment对象
     * - 通过isSpecifiedEnvironment方法判断当前环境是否匹配
     * <p>
     *  xml 配置示例：
     * <pre>
     * &lt;environments default="development"&gt;
     *  &lt;environment id="development"&gt;
     *    &lt;transactionManager type="JDBC"/&gt;
     *    &lt;dataSource type="POOLED"&gt;
     *      &lt;property name="driver" value="${driver}"/&gt;
     *      &lt;property name="url" value="${url}"/&gt;
     *      &lt;property name="username" value="${username}"/&gt;
     *      &lt;property name="password" value="${password}"/&gt;
     *    &lt;/dataSource&gt;
     *  &lt;/environment&gt;
     * &lt;/environments&gt;
     * </pre>
     *
     * @param context environments节点的XNode对象，包含所有environment子节点
     * @throws Exception 如果解析过程中发生异常，如类加载失败、配置错误等
     *
     */
    private void environmentsElement(XNode context) throws Exception {
        // 检查context节点是否为null，防止空指针异常
        if (context != null) {
            // 如果environment字段为null，说明还未设置默认环境
            if (environment == null) {
                // 获取environments节点的default属性作为默认环境ID
                environment = context.getStringAttribute("default");
            }

            // 遍历environments节点下的所有environment子节点
            for (XNode child : context.getChildren()) {
                // 获取当前environment节点的id属性
                String id = child.getStringAttribute("id");
                // 检查当前environment是否是指定的环境
                if (isSpecifiedEnvironment(id)) {
                    // 解析transactionManager子节点，创建事务管理器工厂
                    TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));

                    // 解析dataSource子节点，创建数据源工厂
                    DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));

                    // 从数据源工厂获取数据源实例
                    DataSource dataSource = dsFactory.getDataSource();

                    // 使用建造者模式创建Environment构建器
                    Environment.Builder environmentBuilder = new Environment.Builder(id)
                            // 设置事务管理器工厂
                            .transactionFactory(txFactory)
                            // 设置数据源
                            .dataSource(dataSource);
                    // 构建Environment对象并设置到Configuration中
                    configuration.setEnvironment(environmentBuilder.build());
                }
            }
        }
    }

    /**
     * 解析配置文件中的databaseIdProvider元素，配置数据库标识提供者
     * <p>
     * 该方法负责处理MyBatis配置文件中的<databaseIdProvider>配置，用于根据数据库厂商信息
     * 自动识别数据库类型，并设置对应的databaseId。这使得MyBatis可以在多数据库环境下，
     * 根据不同的数据库类型执行不同的SQL语句。
     *
     * <p>MyBatis内置的DatabaseIdProvider实现：</p>
     * <ul>
     *   <li>DB_VENDOR - 根据数据库产品的名称获取数据库ID</li>
     * </ul>
     *
     * <p>也可以通过实现DatabaseIdProvider接口来自定义数据库标识提供者。</p>
     *
     * <p>配置示例：</p>
     * <pre>{@code
     * <databaseIdProvider type="DB_VENDOR">
     *   <property name="SQL Server" value="sqlserver"/>
     *   <property name="DB2" value="db2"/>
     *   <property name="Oracle" value="oracle" />
     *   <property name="MySQL" value="mysql" />
     *   <property name="PostgreSQL" value="postgresql" />
     * </databaseIdProvider>
     * }</pre>
     *
     * @param context databaseIdProvider节点的XNode对象，包含type属性和property子节点
     * @throws Exception 如果解析过程中发生异常，如类加载失败、实例化失败等
     */
    private void databaseIdProviderElement(XNode context) throws Exception {
        // 初始化DatabaseIdProvider为null
        DatabaseIdProvider databaseIdProvider = null;
        // 检查context节点是否为null，防止空指针异常
        if (context != null) {
            // 获取databaseIdProvider节点的type属性，指定数据库标识提供者的类型
            String type = context.getStringAttribute("type");
            // 为了保持向后兼容性，将旧版本中的"VENDOR"类型转换为"DB_VENDOR"
            // awful patch to keep backward compatibility
            if ("VENDOR".equals(type)) {
                type = "DB_VENDOR";
            }
            // 获取所有property子节点并转换为Properties对象，用于配置数据库标识提供者
            Properties properties = context.getChildrenAsProperties();
            // 根据type属性值解析对应的DatabaseIdProvider类，并创建实例
            databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
            // 设置DatabaseIdProvider的属性，传入从XML配置中读取的Properties对象
            databaseIdProvider.setProperties(properties);
        }
        // 获取当前配置的环境信息
        Environment environment = configuration.getEnvironment();
        // 检查环境和数据库标识提供者是否都不为null
        if (environment != null && databaseIdProvider != null) {
            // 通过数据库标识提供者获取当前数据源的数据库ID
            String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
            // 将获取到的数据库ID设置到配置中
            configuration.setDatabaseId(databaseId);
        }
    }

    /**
     * 解析配置文件中的transactionManager元素，创建事务管理器工厂
     * <p>
     * 该方法负责处理MyBatis配置文件中的<transactionManager>配置，根据配置的类型
     * 创建相应的事务管理器工厂实例，并设置其属性。MyBatis支持两种内置的事务管理器类型：
     * JDBC - 使用JDBC的事务管理机制
     * MANAGED - 让容器来管理事务（如Spring容器）
     * 也可以通过实现TransactionFactory接口来自定义事务管理器。
     * <p>
     * xml 配置示例：
     * <pre>
     *  &lt;transactionManager type="JDBC"/&gt;
     * </pre>
     *
     * @param context transactionManager节点的XNode对象，包含type属性和property子节点
     * @return TransactionFactory 事务管理器工厂实例
     * @throws Exception 如果解析过程中发生异常，如类加载失败、实例化失败等
     */
    private TransactionFactory transactionManagerElement(XNode context) throws Exception {
        // 检查context节点是否为null，防止空指针异常
        if (context != null) {
            // 获取transactionManager节点的type属性，指定事务管理器的类型
            String type = context.getStringAttribute("type");
            // 获取所有property子节点并转换为Properties对象，用于配置事务管理器
            Properties props = context.getChildrenAsProperties();

            // 根据type属性值解析对应的TransactionFactory类，并创建实例
            TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();

            // 设置TransactionFactory的属性，传入从XML配置中读取的Properties对象
            factory.setProperties(props);
            // 返回配置好的TransactionFactory实例
            return factory;
        }
        // 如果context为null，抛出BuilderException异常，提示环境声明需要TransactionFactory
        throw new BuilderException("Environment declaration requires a TransactionFactory.");
    }

    /**
     * 解析配置文件中的dataSource元素，创建数据源工厂
     * <p>
     * 该方法负责处理MyBatis配置文件中的<dataSource>配置，根据配置的类型
     * 创建相应的数据源工厂实例，并设置其属性。MyBatis支持三种内置的数据源类型：
     * UNPOOLED - 不使用连接池，每次请求都打开和关闭连接
     * POOLED - 使用连接池管理数据库连接
     * JNDI - 使用JNDI从应用服务器获取数据源
     * 也可以通过实现DataSourceFactory接口来自定义数据源。
     *
     * @param context dataSource节点的XNode对象，包含type属性和property子节点
     * @return DataSourceFactory 数据源工厂实例
     * @throws Exception 如果解析过程中发生异常，如类加载失败、实例化失败等
     */
    private DataSourceFactory dataSourceElement(XNode context) throws Exception {
        // 检查context节点是否为null，防止空指针异常
        if (context != null) {
            // 获取dataSource节点的type属性，指定数据源的类型
            String type = context.getStringAttribute("type");
            // 获取所有property子节点并转换为Properties对象，用于配置数据源
            Properties props = context.getChildrenAsProperties();
            // 根据type属性值解析对应的DataSourceFactory类，并创建实例
            DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
            // 设置DataSourceFactory的属性，传入从XML配置中读取的Properties对象
            factory.setProperties(props);
            // 返回配置好的DataSourceFactory实例
            return factory;
        }
        // 如果context为null，抛出BuilderException异常，提示环境声明需要DataSourceFactory
        throw new BuilderException("Environment declaration requires a DataSourceFactory.");
    }

    /**
     * 解析配置文件中的typeHandlers元素，注册类型处理器
     * <p>
     * 该方法负责处理MyBatis配置文件中的{@code <typeHandlers>}配置，支持两种方式注册类型处理器：
     * 1. 通过package批量注册指定包下的所有类型处理器
     * 2. 单独注册指定的类型处理器，可以指定javaType和jdbcType的组合
     * <p>
     * 类型处理器(TypeHandler)是MyBatis中用于Java类型和JDBC类型之间转换的核心组件，
     * 通过自定义类型处理器，可以处理特殊的数据类型转换，如枚举、JSON对象等。
     * <p>
     * 配置示例：
     * <pre>{@code
     * <typeHandlers>
     *   <package name="com.example.typehandler"/>
     *   <typeHandler javaType="java.util.Date" jdbcType="TIMESTAMP" handler="com.example.DateTypeHandler"/>
     * </typeHandlers>
     * }</pre>
     *
     * @param parent typeHandlers节点的XNode对象，包含所有typeHandler或package子节点
     * @throws Exception 如果解析过程中发生异常，如类加载失败、实例化失败等
     */
    private void typeHandlerElement(XNode parent) throws Exception {
        // 检查parent节点是否为null，防止空指针异常
        if (parent != null) {
            // 遍历typeHandlers节点下的所有子节点
            for (XNode child : parent.getChildren()) {
                // 处理package方式注册类型处理器（批量注册）
                if ("package".equals(child.getName())) {
                    // 获取package节点的name属性，指定要扫描的包名
                    String typeHandlerPackage = child.getStringAttribute("name");
                    // 注册指定包下的所有类型处理器
                    typeHandlerRegistry.register(typeHandlerPackage);
                } else {
                    // 处理单个typeHandler节点
                    // 获取typeHandler节点的javaType属性，指定Java类型
                    String javaTypeName = child.getStringAttribute("javaType");
                    // 获取typeHandler节点的jdbcType属性，指定JDBC类型
                    String jdbcTypeName = child.getStringAttribute("jdbcType");
                    // 获取typeHandler节点的handler属性，指定类型处理器类
                    String handlerTypeName = child.getStringAttribute("handler");
                    // 将javaType字符串解析为Class对象
                    Class<?> javaTypeClass = resolveClass(javaTypeName);
                    // 将jdbcType字符串解析为JdbcType枚举
                    JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
                    // 将handler字符串解析为Class对象
                    Class<?> typeHandlerClass = resolveClass(handlerTypeName);
                    // 如果指定了javaType
                    if (javaTypeClass != null) {
                        // 如果没有指定jdbcType
                        if (jdbcType == null) {
                            // 注册类型处理器，只关联Java类型
                            typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
                        } else {
                            // 注册类型处理器，同时关联Java类型和JDBC类型
                            typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
                        }
                    } else {
                        // 如果没有指定javaType，只注册类型处理器类
                        typeHandlerRegistry.register(typeHandlerClass);
                    }
                }
            }
        }
    }


    /**
     * 解析配置文件中的 mappers 元素，注册映射器
     * <p>
     * 该方法负责处理 MyBatis 配置文件中的 <mappers> 配置，支持四种方式注册映射器：
     * <ol>
     *   <li>通过 package 批量注册指定包下的所有 Mapper 接口</li>
     *   <li>通过 resource 指定 XML 映射文件路径注册映射器</li>
     *   <li>通过 url 指定 XML 映射文件 URL 路径注册映射器</li>
     *   <li>通过 class 指定 Mapper 接口类名注册映射器</li>
     * </ol>
     * <p>
     * 执行流程：
     * <ol>
     *   <li>遍历 mappers 节点下的所有子节点</li>
     *   <li>对于 package 节点，扫描指定包下的所有 Mapper 接口进行注册</li>
     *   <li>对于 mapper 节点，根据配置的资源类型进行不同处理：
     *     <ul>
     *       <li>resource：从类路径加载 XML 映射文件并解析</li>
     *       <li>url：从 URL 加载 XML 映射文件并解析</li>
     *       <li>class：加载指定的 Mapper 接口类并注册</li>
     *     </ul>
     *   </li>
     *   <li>验证配置合法性，确保只指定一种资源类型</li>
     * </ol>
     * <p>
     * 设计说明：
     * <ul>
     *   <li>使用策略模式处理不同类型的映射器注册方式</li>
     *   <li>XML 映射文件通过 XMLMapperBuilder 解析，支持完整的映射配置</li>
     *   <li>接口方式注册只适用于注解驱动的映射器</li>
     *   <li>错误上下文设置有助于精确定位配置问题</li>
     * </ul>
     * <p>
     * 配置示例：
     * <pre>
     * &lt;mappers&gt;
     *   &lt;package name="com.example.mapper"/&gt;
     *   &lt;mapper resource="mapper/UserMapper.xml"/&gt;
     *   &lt;mapper url="file:///var/mappers/OrderMapper.xml"/&gt;
     *   &lt;mapper class="com.example.mapper.ProductMapper"/&gt;
     * &lt;/mappers&gt;
     * </pre>
     *
     * @param parent mappers 节点的父节点，包含所有 mapper 子节点
     * @throws Exception 如果解析过程中发生异常，如资源找不到、类加载失败等
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