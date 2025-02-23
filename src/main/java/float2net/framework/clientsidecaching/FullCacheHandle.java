package float2net.framework.clientsidecaching;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TrackingArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.support.caching.CacheAccessor;
import io.lettuce.core.support.caching.ClientSideCaching;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 全量缓存处理句柄类
 * <p>
 * 基于redis6以上版本的客户端缓存的广播模式来实现
 *
 * @param <T> redis中value的pojo类型
 * @param <U> 本地内存缓存中value的pojo类型
 */
@Slf4j
public class FullCacheHandle<T, U> extends LocalCacheRepository<T> {

    /**
     * 用法举例：
     * <p>
     * 必须指定的参数是 redisValueType
     * 其他参数未指定时的缺省值：
     * - host: 127.0.0.1
     * - port: 6379
     * - password: <无>
     * - database: 0
     * - collection: <无>
     * - valueFilter: <无>
     * - valueMapper: <无>
     *
     * @param args
     */
    static void main(String[] args) {
        final FullCacheHandle<String, String> handle = FullCacheHandle.<String, String>builder(String.class)
                .withHost("127.0.0.1").withPort(6379).withPassword("").withDatabase(0)
                .withCollection("")
                .withValueFilter(((e) -> e != null))
                .withValueMapper(((e) -> e))
                .build();

        final List<String> redisValues = handle.readRedis();
        final List<String> localValues = handle.readLocal();

    }

    /* 本地缓存 */
    private final Map<String, U> localMapCache = new ConcurrentHashMap<>();

    /* 可选的过滤函数，用于在将Redis数据项写入本地内存缓存之前过滤掉不需要缓存的数据项 */
    private final Predicate<T> valueFilter;

    /* 可选的转换函数，用于将redis中value的pojo类型(T)转换为本地内存缓存中value的pojo类型(U) */
    private final Function<T, U> valueMapper;

    /* 处理redis更新的线程池的数据队列大小，缺省1万. */
    private int queueCapacity;

    /**
     * Return a new {@link FullCacheHandle.Builder} to construct a {@link FullCacheHandle}.
     *
     * @param redisValueType redis中value的pojo类型
     * @param <T>            redis中value的pojo类型
     * @param <U>            本地内存缓存中value的pojo类型
     * @return
     */
    public static <T, U> Builder<T, U> builder(Class<T> redisValueType) {
        return new Builder<T, U>(redisValueType);
    }

    /**
     * 返回一个全量缓存句柄
     *
     * @param host           redis服务ip
     * @param port           redis服务端口
     * @param password       redis访问密码
     * @param database       redis服务database序号
     * @param collection     全量缓存跟踪的key的前缀
     * @param redisValueType redis中value的pojo类型
     * @param valueFilter    可选的过滤函数，用于在将Redis数据项写入本地内存缓存之前过滤掉不需要缓存的数据项
     * @param valueMapper    可选的转换函数，用于将redis中value的pojo类型(T)转换为本地内存缓存中value的pojo类型(U)
     * @param <T>            redis中value的pojo类型
     * @param <U>            本地内存缓存中value的pojo类型
     * @param queueCapacity  处理redis更新的线程池的数据队列大小
     * @return a new instance of {@link FullCacheHandle}
     */
    private static <T, U> FullCacheHandle<T, U> getInstance(String host, int port, String password, int database,
                                                            String collection, Class<T> redisValueType,
                                                            Predicate<T> valueFilter,
                                                            Function<T, U> valueMapper,
                                                            int queueCapacity) {
        final RedisURI redisURI = RedisURI.Builder.redis(host, port)
                .withPassword(new StringBuilder(password)).withDatabase(database).build();
        final RedisCommands<String, String> redisCommands = RedisClient.create(redisURI).connect().sync();
        return new FullCacheHandle<>(redisCommands, collection, redisValueType, valueFilter, valueMapper, queueCapacity);
    }

    /**
     * 实例化一个全量缓存句柄对象
     *
     * @param redisCommands  Lettuce客户端接口（{@link RedisCommands}
     * @param collection     全量缓存跟踪的key的前缀
     * @param redisValueType redis中value的pojo类型
     * @param valueFilter    可选的过滤函数，用于在将Redis数据项写入本地内存缓存之前过滤掉不需要缓存的数据项
     * @param valueMapper    可选的转换函数，用于将redis中value的pojo类型(T)转换为本地内存缓存中value的pojo类型(U)
     * @param queueCapacity  处理redis更新的线程池的数据队列大小
     */
    private FullCacheHandle(RedisCommands redisCommands, String collection,
                            Class<T> redisValueType,
                            Predicate<T> valueFilter,
                            Function<T, U> valueMapper,
                            int queueCapacity) {
        Assert.notNull(redisValueType, "redisValueType不能为空");
        //如果未定义valueFilter，则仅过滤掉null值
        this.valueFilter = Optional.ofNullable(valueFilter).orElse((e) -> e != null);
        //如果未定义valueMapper，则不做任何转换
        this.valueMapper = Optional.ofNullable(valueMapper).orElse((e) -> (U) e);
        super.redisCommands = redisCommands;
        super.redisValueType = redisValueType;
        super.collection = collection;
        this.queueCapacity = queueCapacity;
        TrackingArgs args = TrackingArgs.Builder.enabled().bcast();
        if (StringUtils.hasText(collection)) {
            args = args.prefixes(collection + DELIMITER);
        }
        super.frontend = ClientSideCaching.enable(CacheAccessor.forMap(clientCache),
                redisCommands.getStatefulConnection(), args);
        //断言缓存类型，并添加invalidate消息监听
        Assert.isTrue(super.frontend instanceof ClientSideCaching, "ClientSideCaching类型不匹配");
        ((ClientSideCaching<String, String>) super.frontend).addInvalidationListener(new InvalidationListener());
    }

    /**
     * 直接从redis缓存读出json，并反序列化指定的类型后返回
     *
     * @param id 不包含前缀的id
     * @return redis中的对象
     */
    public T readRedis(String id) {
        final String key = id2key(id);
        try {
            return Optional.ofNullable(redisCommands.get(key))
                    .map(json -> {
                        try {
                            return OBJECT_MAPPER.readValue(json, redisValueType);
                        } catch (JsonProcessingException e) {
                            log.error("反序列化对象异常, key: {}, value: {}, type: {}", key, json, redisValueType, e);
                            return null;
                        }
                    })
                    .orElse(null);
        } catch (Exception e) {
            log.error("读redis异常, key: {}, type: {}", key, redisValueType, e);
            throw e;
        }
    }

    /**
     * 直接从redis缓存读出json，并反序列化指定的类型后返回
     *
     * @param ids 不包含前缀的id列表
     * @return redis中的对象列表
     */
    public List<T> readRedis(String... ids) {
        return Arrays.stream(ids).map(this::readRedis).collect(Collectors.toList());
    }

    /**
     * 直接从redis缓存读出json，并反序列化指定的类型后返回
     *
     * @return redis中的对象列表
     */
    public List<T> readRedis() {
        return super.keys().stream().map(this::key2id).map(this::readRedis).collect(Collectors.toList());
    }

    /**
     * 直接从本地缓存读出对象
     *
     * @param id 不包含前缀的id
     * @return 本地缓存中的对象
     */
    public U readLocal(String id) {
        synchronized (localMapCache) {
            return localMapCache.get(id);
        }
    }

    /**
     * 直接从本地缓存读出对象
     *
     * @param ids 不包含前缀的id列表
     * @return 本地缓存中的对象列表
     */
    public List<U> readLocal(String... ids) {
        synchronized (localMapCache) {
            return localMapCache.keySet().stream().map(localMapCache::get).collect(Collectors.toList());
        }
    }

    /**
     * 直接从本地缓存读出对象
     *
     * @return 本地缓存中的对象列表
     */
    public List<U> readLocal() {
        synchronized (localMapCache) {
            return localMapCache.values().stream().collect(Collectors.toList());
        }
    }

    /**
     * 将对象写入缓存
     *
     * @param id    不包含前缀的id
     * @param value 待写入的对象
     * @return 写入的对象
     */
    public T save(String id, T value) {
        return super.save(id, value);
    }

    /**
     * 将对象从缓存中删除
     *
     * @param id 不包含前缀的id
     * @return 是否删除成功
     */
    public boolean delete(String id) {
        return super.delete(id);
    }

    /**
     * 从缓存中删除全部对象（仅限于指定的collection）
     *
     * @return 删除的对象个数
     */
    public long clear() {
        return super.clear();
    }

    /**
     * 将redis里面的内容全量同步到本地缓存（一般用于定时兜底策略）
     * null值不会缓存到本地
     */
    public void sync() {
        synchronized (localMapCache) {
            localMapCache.clear();
            super.keys().stream().map(this::key2id).forEach(id -> {
                final T t = readRedis(id);
                // redis中缓存的null值不要缓存到本地
                if (t != null) {
                    // 先过滤
                    if (valueFilter.test(t)) {
                        // 再转换，保存
                        localMapCache.put(id, valueMapper.apply(t));
                    }
                }
            });
        }
    }

    /**
     * invalidate消息监听类
     */
    private class InvalidationListener implements Consumer<String> {

        private ThreadFactory threadFactory = new ThreadFactoryBuilder()
                .setNameFormat("FullCacheHandler-thread-%d")
                .setDaemon(false).setPriority(Thread.NORM_PRIORITY).build();

        /**
         * 使用线程池来管理redis状态更新到本地的任务线程
         * 线程数最大100，排队队列长度缺省1万（可配置）
         * 队列溢出策略为抛弃最老的数据。
         */
        private ThreadPoolExecutor poolExecutor = new ThreadPoolExecutor(
                100, 100, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), threadFactory, new ThreadPoolExecutor.DiscardOldestPolicy());

        @Override
        public void accept(String key) {
            log.info("invalidate key: {}", key);
            // XXX 这里有个坑：这里同一个线程读不到redis里的值，需要另外开线程才能读到redis里的最新值！！！
//            updteaLocalMapCache(key);
            poolExecutor.execute(() -> updateLocalMapCache(key));
        }

        /**
         * 从redis拉取key的最新值，转换成本地对象类型后，更新到本地缓存中。
         * 如果redis中key已经不存在，则将本地缓存中的key删除。
         *
         * @param key
         */
        private void updateLocalMapCache(String key) {
            //这里有可能是过期的key，因此注意这里不能再次拉到redis。
            final String id = key2id(key);
            final T redisValue = readRedis(id);
            if (redisValue == null) {
                synchronized (localMapCache) {
                    log.info("检测到invalidate key：{}，从本地缓存中删除", key);
                    localMapCache.remove(id);
                }
            } else {
                synchronized (localMapCache) {
                    log.info("检测到invalidate key：{}, 更新值到本地缓存中: {}", key, redisValue);
                    //先过滤，再缓存
                    if (valueFilter.test(redisValue)) {
                        localMapCache.put(id, valueMapper.apply(redisValue));
                    }else {
                    	localMapCache.remove(id);
                    }
                }
            }
        }

    }

    public static class Builder<T, U> {
        private String host = "127.0.0.1";
        private int port = 6379;
        private String password = "";
        private int database = 0;
        private Predicate<T> valueFilter = (e -> e != null);
        private Function<T, U> valueMapper = (e -> (U) e);
        private Class<T> redisValueType;
        private String collection = "";
        private Integer queueCapacity = 10000;

        private Builder(Class<T> redisValueType) {
            Assert.notNull(redisValueType, "redisValueType不能为空");
            this.redisValueType = redisValueType;
        }

        public Builder<T, U> withHost(String host) {
            this.host = host;
            return this;
        }

        public Builder<T, U> withPort(int port) {
            this.port = port;
            return this;
        }

        public Builder<T, U> withPassword(String password) {
            this.password = password;
            return this;
        }

        public Builder<T, U> withDatabase(int database) {
            this.database = database;
            return this;
        }

        public Builder<T, U> withCollection(String collection) {
            this.collection = collection;
            return this;
        }

        public Builder<T, U> withValueFilter(Predicate<T> valueFilter) {
            this.valueFilter = valueFilter;
            return this;
        }

        public Builder<T, U> withValueMapper(Function<T, U> valueMapper) {
            this.valueMapper = valueMapper;
            return this;
        }

        public Builder<T, U> withQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        public FullCacheHandle<T, U> build() {
            final FullCacheHandle<T, U> instance = getInstance(host, port, password, database,
                    collection, redisValueType, valueFilter, valueMapper, queueCapacity);
            //立刻执行一次缓存同步
            instance.sync();
            return instance;
        }
    }

}
