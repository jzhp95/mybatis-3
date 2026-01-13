package xyz.jzhp.demo;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.Test;
import xyz.jzhp.demo.dao.PersonMapper;
import xyz.jzhp.demo.domain.Person;

public class MybatisTest {

    @Test
    public void test() throws Exception{

        // 加载配置文件，最终构建出 SqlSessionFactory 实例
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(Resources.getResourceAsStream("mybatis-config.xml"));

        // DefaultSqlSession -> Executor -> Transaction -> Connection
        try (SqlSession sqlSession = sqlSessionFactory.openSession()) {
            // 拿到的实际上是一个 JDK 动态代理对象，真正执行的是 MapperProxy 类的 invoke 方法
            PersonMapper personMapper = sqlSession.getMapper(PersonMapper.class);
            Person person = personMapper.selectPersonById(1);
            System.out.println(person);
        }
    }
}
