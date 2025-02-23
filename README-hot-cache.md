## 热点缓存用法

热点缓存采用java注解方式实现，使用方法与`spring-cache`基本一致, 在bean的方法上加上注释即可。
为了避免同一个类内部方法间调用, 注解无法生效的问题(spring-cache也同样有这样的问题)，
建议写一个单独的用于缓存访问的spring component，或者嵌入到主访问类中作为私有静态内部类。

以下用法举例

1. 定义一个缓存访问类CacheDao:

    ```java
    @Component
    public class CacheDao {
    
       /**
        * 访问底层数据库的repository
        */
       @Resource
       private SomeRepository repository;
    
       /**
        * 从缓存读（如果缓存没有该值，则会执行方法体得到的结果写入redis，然后返回）
        *
        * [value] 是将redis中key的前缀部分，组合value+":"+id将得到最终redis中实际的key值
        * [type] 是将redis中String类型的值反序列化为POJO对象的类型
        * 
        * @param id 对象的ID
        * @return 读取到的对象
        */
       @LocalCacheable(value = SOME_PREFIX, type = SomeType.class)
       public SomeType findById(String id) {
          return (SomeType) repository.findById(id);
       }
    
       /**
        * 如果返回的是一个List, 则需要设置： isList = true 
        */
       @LocalCacheable(value = SOME_PREFIX, type = SomeType.class, isList = true)
       public List<SomeType> findById(String id) {
          return (SomeType) repository.findById(id);
       }
    
       /**
        * 从缓存读指定前缀的所有值(allEntries = true)，返回List
        * 此方法不会访问方法本体内代码，因此方法体内部写return null即可
        * 
        * 需要注意的是，此方法是将redis里的prefix下的所有键值返回，会调用redis的keys命令
        * 如果键值较多，则可能降低redis的性能，如果需要频繁这样操作，可能使用全量缓存更合适。
        * 
        * @return 返回对象列表
        */
       @LocalCacheable(value = SOME_PREFIX, type = SomeType.class, allEntries = true)
       public List<SomeType> findAllFromCache() {
          return null;
       }
   
       /**
        * 当所访问的键值为Null且很频繁时，会击穿缓存去访问底层，影响性能，
        * 此时可以根据业务场景需要，将Null值缓存在本地内存一段时间（ttl），
        * 直到过期后再去访问一次底层获取最新值（如果还是Null则会继续缓存），
        * 这样可以在业务允许的前提下，提高访问效率，避免缓存被频繁击穿而影响性能。
        * 
        * 例如下面的代码是设置每次Null值缓存时长为60秒。
        *
        * @return 返回对象列表
        */
       @LocalCacheable(value = SOME_PREFIX, type = SomeType.class, nullCacheTtl = 60)
       public List<SomeType> findAllFromCache() {
          return null;
       }
    
       /**
        * 先调用方法体，然后将返回结果写入缓存
        * @return 返回保存的对象
        */
       @LocalCachePut(value = SOME_PREFIX, type = SomeType.class)
       public SomeType save(String apId, SomeEntity entity) {
          return repository.save(entity); //比如，将记录保存到数据库
       }
    
       /**
        * 先调用方法体，然后将返回结果写入缓存，Null值缓存到本地内存且设置存活(ttl)为60秒
        * @return 返回保存的对象
        */
       @LocalCachePut(value = SOME_PREFIX, type = SomeType.class, nullCacheTtl = 60)
       public SomeType save(String apId, SomeEntity entity) {
          return repository.save(entity); //比如，将记录保存到数据库
       }
    
       /**
        * 先调用方法体，然后将返回结果写入缓存, 并设置redis中该值的存活时间（ttl）
        * @param ttl redis中为该值设置的存活时间（秒）
        * @return 返回保存的对象
        */
       @LocalCachePut(value = SOME_PREFIX, type = SomeType.class)
       public SomeType save(String apId, SomeEntity entity, long ttl) {
          return repository.save(entity); //比如，将记录保存到数据库
       }
    
       /**
        * 从redis缓存中删除某条（id）记录
        * 并可指定"beforeInvocation"来选择在删除缓存前，或在删除缓存后，执行方法体本体代码
        */
       @LocalCacheEvict(value = SOME_PREFIX, type = SomeType.class)
       public void delete(String id) {
          repository.deleteById(id); //比如，从数据库删除相应记录
       }

       /**
        * 从redis缓存中删除某全部记录（allEntries=true）
        * 并可指定"beforeInvocation"来选择在删除缓存前，或在删除缓存后，执行方法体本体代码
        */
       @LocalCacheEvict(value = SOME_PREFIX, type = SomeType.class, allEntries = true)
       public void clear() {
          log.info("all entries cleared from redis");
       }
    }
    ```

2. 其他模块注入上面的CacheDao, 进行缓存访问操作
   ```java
    @Resource
    private CacheDao cacheDao; 
    //业务代码, 通过cacheDao操作缓存
   ...
   cacheDao.findById(someID);
   ...
   ```

3. 如果是将CacheDao定义为主访问类的私有静态内部类，则是这样的：
    ```java
    @Resource
    private CacheDao cacheDao; 
    //业务代码, 通过cacheDao操作缓存
    ...
    cacheDao.findById(someID);
    ...
   
    //将CacheDao作为私有静态内部类纳入进来，可以达到对外部屏蔽的效果
    private static class CacheDao {
        //...
    }
   ```
