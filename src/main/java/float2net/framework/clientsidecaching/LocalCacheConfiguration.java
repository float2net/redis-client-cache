package float2net.framework.clientsidecaching;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import javax.annotation.Resource;

/**
 * 配置Lettuce6连接参数以支持客户端缓存功能。
 */
@Configuration
@ConditionalOnProperty(prefix = "float2net.client-side-cache.redis", name = "enabled", havingValue = "true")
public class LocalCacheConfiguration {

    @Value("${float2net.client-side-cache.redis.host:localhost}")
    private String host;

    @Value("${float2net.client-side-cache.redis.port:6379}")
    private Integer port;

    @Value("${float2net.client-side-cache.redis.password:}")
    private String password;

    @Value("${float2net.client-side-cache.redis.database:0}")
    private Integer database;

    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient() {
        RedisURI redisURI = RedisURI.Builder.redis(host, port).withPassword(new StringBuilder(password))
                .withDatabase(database).build();
        return RedisClient.create(redisURI);
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, String> redisConnection(RedisClient client) {
        return client.connect();
    }

    @Bean
    public RedisCommands redisCommands(StatefulRedisConnection<String, String> connection) {
        return connection.sync();
    }

    @Resource
    private BeanFactory beanFactory;

    /**
     * to instantiate a bean which have runtime constructor arguments.
     * <p>
     * ref: https://stackoverflow.com/questions/35108778/spring-bean-with-runtime-constructor-arguments
     *
     * @param collection
     * @param type
     * @return
     */
    @Bean
    @Scope(value = "prototype")
    //Spring will not instantiate "prototype" bean right on start, but will do it later on demand.
    public LocalCacheRepository localCacheRepository(String collection, Class type, int nullCacheTtl) {
//        RedisCommands redisCommands = SpringContextUtils.getBean("redisCommands", RedisCommands.class); //FIXME: return null
        RedisCommands redisCommands = beanFactory.getBean(RedisCommands.class); //will only apply to 'prototype' bean
        return new LocalCacheRepository(redisCommands, collection, type, nullCacheTtl);
    }
}
