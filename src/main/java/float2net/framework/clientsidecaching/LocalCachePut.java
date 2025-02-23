package float2net.framework.clientsidecaching;

import java.lang.annotation.*;

/**
 * 使用客户端缓存的注解，用于基于 redis6 和 Lettuce6 的客户端缓存
 * 切面代码在 LettuceCacheAspect
 * <p>
 * 使用方式类似于 Spring Cache 的 @CachePut 注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LocalCachePut {

    /**
     * redis key prefix, e.g. "viid_config", like 'value' in @CachePut
     *
     * @return
     */
    String value();

    /**
     * redis value type, e.g. ConfigEntity.class
     *
     * @return
     */
    Class type();

    /**
     * Set the value and retain the existing TTL
     * 这是针对redis里面key的ttl设置，更改值的同时保留原有ttl原封不动
     */
    boolean keepTtl() default false;

    /**
     * 将null值缓存到本地缓存的ttl时长（秒），
     * -1: 缺省值，表示不保存null值
     * 0: 没有ttl, 永久保存null（服务重启后才失效）
     * >0: 以秒为单位的ttl时长
     */
    int nullCacheTtl() default -1;

}
