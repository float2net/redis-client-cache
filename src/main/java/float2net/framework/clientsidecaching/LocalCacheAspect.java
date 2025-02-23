package float2net.framework.clientsidecaching;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.annotation.Resource;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * LocalCacheable, LocalCachePut, LocalCacheEvict注解的切面代码
 */
@Component
@Aspect
@Slf4j
public class LocalCacheAspect {

    @Resource
    private BeanFactory beanFactory;

    //容纳每种类型的客户端缓存实例
    private static final ConcurrentMap<String, LocalCacheRepository> localCacheRepositoryMap = new ConcurrentHashMap<>();

    /**
     * 从本地缓存读（没有则从redis读）
     * <p>
     * 首先需要初始化本地缓存对象并放入map（如果是第一次使用该缓存）
     * <p>
     * 然后先尝试从本地缓存找，如果找到，则返回。
     * 如果缓存内找不到（表明redis里也没有），则执行方法内代码（pjp.proceed）
     * 该方法内部代码一般是从数据库查找并缓存到redis，然后将方法体返回结果放入缓存，并返回。
     *
     * @param pjp
     * @param localCacheable
     * @return
     * @throws Throwable
     */
    @Around(value = "@annotation(localCacheable)")
    public Object aroundLocalCacheable(ProceedingJoinPoint pjp, LocalCacheable localCacheable) throws Throwable {
        final String prefix = localCacheable.value();
        final Class type = localCacheable.type();
        final int nullCacheTtl = localCacheable.nullCacheTtl();

        final Object[] args = pjp.getArgs();

        if (log.isDebugEnabled()) {
            log.debug("method " + pjp.getSignature().getName() + " is around on " + pjp.getThis() + " with args " + args);
            log.debug("@LocalCacheable: " + localCacheable);
        }

        LocalCacheRepository localCacheRepository = getOrCreateLocalCacheRepository(prefix, type, nullCacheTtl);

        if (localCacheable.allEntries()) { //返回当前集合的所有对象，这只查找缓存
            return localCacheRepository.findAll();
        } else { // 仅仅返回指定key的对象，先查找缓存，如果缓存没有，则调用方法体内部代码查找，并写回缓存
            Assert.notEmpty(args, "方法参数不可为空");
            final String key = Optional.ofNullable(args[0]).map(Object::toString)
                    .orElseThrow(() -> new Exception("LocalCacheable key(args[0]) cannot be null"));
            Object obj;
            if (localCacheable.isList()) { //这是一个List<T>
                obj = localCacheRepository.findList(key);
            } else { //这是一个<T>
                obj = localCacheRepository.findOne(key);
            }
            if (obj == null) { //当前缓存里没有
                //先尝试访问null-cache，如有则返回null
                if (localCacheRepository.presentInNullCache(key)) {
                    return null;
                }
                //否则执行方法体并将返回结果存入缓存
                obj = pjp.proceed();
                if (obj == null) {
                    //空值先尝试存入null-cache（如果存在null-cache）
                    localCacheRepository.saveToNullCache(key);
                    return null; //null不要存入redis，没有意义
                }
                obj = localCacheRepository.save(key, obj);
            }
            return obj;
        }
    }

    /**
     * 先调用方法体，然后将返回结果放入缓存, 可附加ttl参数
     *
     * @param pjp
     * @param localCachePut
     * @return
     */
    @Around(value = "@annotation(localCachePut)")
    public Object aroundLocalCachePut(ProceedingJoinPoint pjp, LocalCachePut localCachePut) throws Throwable {
        final String prefix = localCachePut.value();
        final Class type = localCachePut.type();
        final int nullCacheTtl = localCachePut.nullCacheTtl();

        Object[] args = pjp.getArgs();

        Assert.isTrue(args != null && args.length >= 2, "方法参数长度至少为2");

        final String key = Optional.ofNullable(args[0]).map(Object::toString)
                .orElseThrow(() -> new Exception("LocalCachePut key(args[0]) cannot be null"));

        if (log.isDebugEnabled()) {
            log.debug("method " + pjp.getSignature().getName() + " is around on " + pjp.getThis() + " with args " + args);
            log.debug("@LocalCachePut: " + localCachePut);
        }

        LocalCacheRepository localCacheRepository = getOrCreateLocalCacheRepository(prefix, type, nullCacheTtl);

        //将方法体调用结果放入缓存并返回。
        final Object result = pjp.proceed();
        if (result == null) {
            //空值先尝试存入null-cache（如果存在null-cache）
            localCacheRepository.saveToNullCache(key);
            return null; //null不要存入redis，没有意义
        }
        if (args.length >= 3) { //FIXME: ttl是第三个参数！
            long ttl = (long) args[2];
            return localCacheRepository.save(key, result, ttl);
        } else {
            return localCacheRepository.save(key, result, localCachePut.keepTtl());
        }
    }

    /**
     * 从redis缓存中删除某条记录，可选删除全部记录
     * 并可选择在删除缓存前，或在删除缓存后，执行方法体代码。
     *
     * @param pjp
     * @param localCacheEvict
     * @return
     */
    @Around(value = "@annotation(localCacheEvict)")
    public Object aroundLocalCacheEvict(ProceedingJoinPoint pjp, LocalCacheEvict localCacheEvict) throws Throwable {
        final Object[] args = pjp.getArgs();

        if (log.isDebugEnabled()) {
            log.debug("method " + pjp.getSignature().getName() + " is around on " + pjp.getThis() + " with args " + args);
            log.debug("@LocalCacheEvict: " + localCacheEvict);
        }

        if (localCacheEvict.beforeInvocation()) {
            //先删除缓存，后执行方法体
            evictCache(args, localCacheEvict);
            pjp.proceed();
        } else {
            //先执行方法体，后删除缓存
            pjp.proceed();
            evictCache(args, localCacheEvict);
        }

        return true;
    }

    private void evictCache(Object[] args, LocalCacheEvict localCacheEvict) throws Exception {
        final String prefix = localCacheEvict.value();
        LocalCacheRepository localCacheRepository = getLocalCacheRepository(prefix);
        if (localCacheRepository != null) {
            if (localCacheEvict.allEntries()) { //删除该类型的所有key
                localCacheRepository.clearNullCache();
                localCacheRepository.clear();
                //+++FIXME: 广播模式下，如果要删除repository，还要同时删除invalidation监听器。
                // 然而现在的ClientSideCaching接口没有提供相应的删除方法，所以这里暂时作罢。
//            localCacheRepositoryMap.remove(localCacheEvict.value()); //此时需要回收本地缓存空间
                //+++
            } else { //删除单个key
                Assert.notEmpty(args, "方法参数不可为空");
                //从redis缓存删除对象
                final String key = Optional.ofNullable(args[0]).map(Object::toString)
                        .orElseThrow(() -> new Exception("LocalCacheEvict key(args[0]) cannot be null"));
                localCacheRepository.deleteFromNullCache(key);
                localCacheRepository.delete(key);
            }
        }
    }

    private LocalCacheRepository getLocalCacheRepository(String prefix) {
        return localCacheRepositoryMap.get(prefix);
    }

    private LocalCacheRepository getOrCreateLocalCacheRepository(String prefix, Class type, int nullCacheTtl) {
        //如果该类型是第一次使用，则初始化本地缓存对象
        return localCacheRepositoryMap.computeIfAbsent(prefix,
                // instantiate the Bean with runtime constructor arguments.
                (k) -> beanFactory.getBean(LocalCacheRepository.class, prefix, type, nullCacheTtl)
        );
    }
}
