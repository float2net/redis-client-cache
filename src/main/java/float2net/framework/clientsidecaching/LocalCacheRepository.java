package float2net.framework.clientsidecaching;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.lettuce.core.SetArgs;
import io.lettuce.core.TrackingArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.support.caching.CacheAccessor;
import io.lettuce.core.support.caching.CacheFrontend;
import io.lettuce.core.support.caching.ClientSideCaching;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 支持基于Redis6以及Lettuce6的客户端缓存泛型类。
 * 目前支持将pojo对象序列化后写redis string，可以扩展以支持更多redis数据类型的访问。
 *
 * @param <T> 作为value存储的pojo对象类型
 *            <p>
 *            refer: https://redis.io/topics/client-side-caching
 */
@Slf4j
public class LocalCacheRepository<T> {

    // key delimiter
    static final String DELIMITER = ":";

    // the key prefix (without delimiter)
    String collection;

    // the generic type
    Class<T> redisValueType;

    // the client-side cache (do NOT access directly!)
    final Map<String, T> clientCache = new ConcurrentHashMap<>();

    // the frontend through which we're going to access the client-side cache
    CacheFrontend<String, String> frontend;

    // a GUAVA cache used for null values holder within a period of time (ttl)
    LoadingCache<String, String> nullCache;

    // the Lettuce command API
    protected RedisCommands<String, String> redisCommands;

    protected static final ObjectMapper OBJECT_MAPPER;

    static final TimeZone DEFAULT_TIMEZONE = TimeZone.getTimeZone("UTC");

    /* static initializer */
    static {
        OBJECT_MAPPER = new ObjectMapper();
        OBJECT_MAPPER.setTimeZone(DEFAULT_TIMEZONE);
        //反序列化时，忽略未知属性
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        //序列化时，不包含null属性值
        OBJECT_MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * 标准模式的构造器
     *
     * @param redisCommands
     * @param redisValueType
     * @param collection
     */
    LocalCacheRepository(RedisCommands redisCommands, String collection, Class<T> redisValueType) {
        this(redisCommands, collection, redisValueType, -1);
    }

    /**
     * 标准模式的构造器
     *
     * @param redisCommands
     * @param redisValueType
     * @param collection
     * @param nullCacheTtl
     */
    LocalCacheRepository(RedisCommands redisCommands, String collection, Class<T> redisValueType, int nullCacheTtl) {
        this.redisCommands = redisCommands;
        this.redisValueType = redisValueType;
        this.collection = collection;
        this.frontend = ClientSideCaching.enable(CacheAccessor.forMap(clientCache),
                redisCommands.getStatefulConnection(), TrackingArgs.Builder.enabled());
        if (nullCacheTtl > 0) {
            log.debug("在集合{}上创建本地空值缓存，存活时间{}秒", collection, nullCacheTtl);
            this.nullCache = CacheBuilder.newBuilder()
                    .expireAfterWrite(nullCacheTtl, TimeUnit.SECONDS)
                    .build(new CacheLoader<String, String>() {
                        @Override
                        public String load(String key) {
                            return "";
                        }
                    });
        }

        /**
         * 启用一个定时心跳保活任务，防止连接闲置超时（一般是5分钟）后被redis服务端断开连接
         */
        Timer timer = new Timer(true); //running timer task as daemon thread
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                log.info("say hello to redis, collection is {}", collection);
                redisCommands.get("hello");
            }
        }, 0, 60 * 1000);
    }

    LocalCacheRepository() {
    }

    /**
     * 在 null-cache 中是否还存在（未过期）
     *
     * @param id
     * @return
     */
    boolean presentInNullCache(String id) {
        if (nullCache != null) {
            if (nullCache.getIfPresent(id) != null) {
                if (log.isDebugEnabled()) {
                    log.debug("在集合{}上hit(命中)空值缓存id:{}", collection, id);
                }
                return true;
            }
            if (log.isDebugEnabled()) {
                log.debug("在集合{}上miss(未命中)空值缓存id:{}", collection, id);
            }
        }
        return false;
    }

    /**
     * 保存到 null-cache
     *
     * @param id
     */
    void saveToNullCache(String id) {
        if (nullCache != null) {
            nullCache.put(id, "null");
            if (log.isDebugEnabled()) {
                log.debug("在集合{}上缓存空值缓存id:{}", collection, id);
            }
        }
    }

    /**
     * 从null-cache中移除
     *
     * @param id
     */
    void deleteFromNullCache(String id) {
        if (nullCache != null) {
            nullCache.invalidate(id);
            if (log.isDebugEnabled()) {
                log.debug("在集合{}上删除空值缓存id:{}", collection, id);
            }
        }
    }

    /**
     * 清空null-cache
     */
    void clearNullCache() {
        if (nullCache != null) {
            nullCache.invalidateAll();
            if (log.isDebugEnabled()) {
                log.debug("在集合{}上清空空值缓存", collection);
            }
        }
    }

    /**
     * return all keys in current collection (match pattern)
     * <p>
     * pattern使用通配符
     *
     * @return raw keys list
     */
    List<String> keys() {
        return keys(collection);
    }

    /**
     * return all keys in the specific collection (match pattern)
     * <p>
     * pattern使用通配符
     *
     * @param collection
     * @return raw keys list
     */
    List<String> keys(String collection) {
        String pattern = pattern(collection);
        final long stime = System.currentTimeMillis();
        final List<String> keys = redisCommands.keys(pattern);
        if (log.isWarnEnabled()) {
            final long duration = System.currentTimeMillis() - stime;
            if (duration >= 100) {
                log.warn("调用Redis的\"KEYS\"命令耗时{}ms，可能会阻塞Redis主线程", duration);
            }
        }
        return keys;
    }

    /**
     * save with expiration
     *
     * @param id
     * @param object
     * @param ex     expiration seconds
     * @return
     */
    T save(String id, T object, long ex) {
        String key = id2key(id);
        try {
            String jsonObject = OBJECT_MAPPER.writeValueAsString(object);
            redisCommands.setex(key, ex, jsonObject);
            return object;
        } catch (Exception e) {
            log.error("Unable to save value'{}'", object, e);
            return null;
        }
    }

    /**
     * save (without expiration)
     *
     * @param key
     * @param object
     * @return
     */
    T save(String key, T object) {
        return save(key, object, false);
    }

    /**
     * save (optional keep expiration intact)
     *
     * @param id
     * @param object
     * @param keepTtl keep expiration intact or not
     * @return
     */
    T save(String id, T object, Boolean keepTtl) {
        String key = id2key(id);
        try {
            String jsonObject = OBJECT_MAPPER.writeValueAsString(object);
            if (keepTtl) {
                redisCommands.set(key, jsonObject, SetArgs.Builder.keepttl());
            } else {
                redisCommands.set(key, jsonObject);
            }
            return object;
        } catch (Exception e) {
            log.error("Unable to save value '{}'", object, e);
            return null;
        }
    }

    /**
     * delete on key
     *
     * @param id
     * @return
     */
    boolean delete(String id) {
        String key = id2key(id);
        try {
            Long n = redisCommands.del(key);
            return n == 1;
        } catch (Exception e) {
            log.error("Unable to delete key {} '{}'", key, e);
            return false;
        }
    }

    /**
     * delete multiple keys
     *
     * @param keys
     * @return
     */
    Long delete(String... keys) {
        final long[] n = {0};
        IntStream.range(0, keys.length).forEach(x -> {
            String key = keys[x];
            if (this.delete(key)) {
                n[0]++;
            }
        });
        return n[0];
    }

    /**
     * clear all keys in this collection (match pattern)
     * <p>
     * pattern使用通配符
     *
     * @return
     */
    long clear() {
        String[] rawKeys = this.keys(collection).toArray(new String[0]);
        if (rawKeys.length > 0) {
            return redisCommands.del(rawKeys);
        } else {
            return 0L;
        }
    }

    /**
     * find by key
     *
     * @param id
     * @return
     */
    T findOne(String id) {
        return find(id2key(id));
    }

    /**
     * find list by key
     *
     * @param id
     * @return
     */
    List<T> findList(String id) {
        String key = id2key(id);
        try {
            String jsonObj = frontend.get(key);
            if (jsonObj == null) {
                return null;
            } else {
                List<T> list = OBJECT_MAPPER.readValue(jsonObj, new TypeReference<List<T>>() {
                }); //这里转换出来的list元素类型实际上不是type类型，而是一个map，所以需要下面再做个转换
                return list.stream().map(x -> OBJECT_MAPPER.convertValue(x, redisValueType)).collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.error("Unable to find or parse entry '{}' ", key, e);
            return null;
        }
    }

    /**
     * find by key (from client-side cache)
     *
     * @param key the RAW key
     * @return
     */
    T find(String key) {
        try {
            String jsonObj = frontend.get(key);
            if (jsonObj == null) {
                return null;
            } else {
                return OBJECT_MAPPER.readValue(jsonObj, redisValueType);
            }
        } catch (Exception e) {
            //>>>在兰州的arm环境，OracleJDK版本，出现从本地缓存读到的值是错的（与redis的值不同步），
            // 且不是正确的类型，导致这里抛异常，临时解决办法是直接从redis读，会损失性能。
            // 改为部署OpenJDK后，症状消失。
            log.error("Unable to find or parse entry '{}' from client-side cache, will fall back on REDIS directly", key, e);
            return findFromRedis(key);
            //<<<
        }
    }

    /**
     * find by key (from REDIS directly)
     * <p>
     * 当从本地缓存查找失败（类型不匹配，比如现场ARM环境下的异常），才调用这个方法直接从REDIS取值并返回。
     *
     * @param key the RAW key
     * @return
     */
    T findFromRedis(String key) {
        try {
            String jsonObj = redisCommands.get(key); //从redis读
            if (jsonObj == null) {
                return null;
            } else {
                return OBJECT_MAPPER.readValue(jsonObj, redisValueType);
            }
        } catch (Exception e) {
            log.error("Unable to find or parse entry '{}' from REDIS", key, e);
            return null;
        }
    }

    /**
     * find all by key list
     *
     * @param keys the RAW keys
     * @return
     */
    List<T> find(String... keys) {
        return Arrays.stream(keys).map(this::find).collect(Collectors.toList());
    }

    /**
     * find all in this collection
     *
     * @return
     */
    List<T> findAll() {
        final String[] keys = this.keys().toArray(new String[0]);
        return this.find(keys);
    }

    /**
     * 将redis key转换为pojo对象id
     *
     * @param key
     * @return
     */
    String key2id(String key) {
        if (StringUtils.hasText(collection)) {
            return key.replaceFirst(collection + DELIMITER, "");
        } else {
            return key;
        }
    }

    /**
     * 将pojo对象id转换为redis key
     *
     * @param id
     * @return
     */
    String id2key(String id) {
        if (StringUtils.hasText(collection)) {
            return collection + DELIMITER + id;
        } else {
            return id;
        }
    }

    private String pattern(String collection) {
        if (StringUtils.hasText(collection)) {
            return collection + DELIMITER + "*";
        } else {
            return "*";
        }
    }

    static void main(String[] args) throws JsonProcessingException {
        Assert.isTrue("null".equals(OBJECT_MAPPER.writeValueAsString(null)), "");
        Assert.isNull(OBJECT_MAPPER.readValue("null", String.class), "");
        System.out.println("ok");
    }
}
