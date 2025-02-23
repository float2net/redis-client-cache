package float2net.framework.clientsidecaching;

import java.lang.annotation.*;

/**
 * 使用客户端缓存的注解，用于基于 redis6 和 Lettuce6 的客户端缓存
 * 切面代码在 LettuceCacheAspect
 * <p>
 * 使用方式类似于 Spring Cache 的 @Cacheable 注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LocalCacheable {

    /**
     * redis key prefix, e.g. "viid_config", like 'value' in @Cacheable
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
     * 缓存记录是否是List<type>类型
     *
     * @return
     */
    boolean isList() default false;

    /**
     * 返回redis缓存中的所有记录（不执行方法体）
     * 此操作需要调用redis的keys命令
     */
    boolean allEntries() default false;

    /**
     * 将null值缓存到本地缓存的ttl时长（秒），
     * -1: 缺省值，表示不保存null值
     * 0: 没有ttl, 永久保存null（服务重启后才失效）
     * >0: 以秒为单位的ttl时长
     */
    int nullCacheTtl() default -1;

}
