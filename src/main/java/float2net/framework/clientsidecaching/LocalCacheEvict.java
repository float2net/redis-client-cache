package float2net.framework.clientsidecaching;

import java.lang.annotation.*;

/**
 * 使用客户端缓存的注解，用于基于 redis6 和 Lettuce6 的客户端缓存
 * 切面代码在 LettuceCacheAspect
 * <p>
 * 使用方式类似于 Spring Cache 的 @CacheEvict 注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LocalCacheEvict {

    /**
     * redis key prefix, e.g. "viid_config", like 'value' in @CacheEvict
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
     * Whether all the entries inside the cache(s) are removed.
     * <p>By default, only the value under the associated key is removed.
     * <p>Note that setting this parameter to {@code true} and specifying a
     * key is not allowed.
     */
    boolean allEntries() default false;

    /**
     * 是否在执行方法体之前就删除缓存（缺省是先执行方法体，再删除缓存）
     *
     * @return
     */
    boolean beforeInvocation() default false;

}
