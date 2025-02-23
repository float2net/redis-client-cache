## 全量缓存用法

1. 使用Builder创建一个FullCacheHandle实例

   ```java
   final FullCacheHandle<T, U> handle = //<T,U>分别对应redis中value反序列化的类型，和本地全量缓存中存储的对象类型
        FullCacheHandle.<T, U>builder(T.class) //redis中value的反序列化类型
            .withHost("127.0.0.1") // redis服务主机地址
            .withPort(6379) //redis服务端口
            .withPassword("") //redis访问密码
            .withDatabase(0) //redis服务database序号
            .withCollection("prefix") //全量缓存跟踪的key的前缀
            .withValueFilter(((e) -> e != null)) //可选的过滤函数，用于在将Redis数据项写入本地内存缓存之前过滤掉不需要缓存的数据项，缺省过滤掉null值。
            .withValueMapper(((e) -> e)) //可选的转换函数，用于将redis中value的pojo类型(T)转换为本地内存缓存中value的pojo类型(U)，缺省不做转换。
            .build();
   ```

2. 调用FullCacheHandle的公共方法

   * 直接从redis缓存读对象

   ```java
   //读单个对象
   handle.readRedis(id);
   //读多个对象
   handle.readRedis(id1, id2, id3, ...);
   //读全部对象
   handle.readRedis();
   ```
   
   * 直接从本地内存缓存读对象

   ```java
   //读单个对象
   handle.readLocal(id);
   //读多个对象
   handle.readLocal(id1, id2, id3, ...);
   //读全部对象
   handle.readLocal();
   ```
   
   * 将单个对象写入缓存

   ```java
   handle.save(id, value);
   ```

   * 将单个对象从缓存中删除

   ```java
   handle.delete(id);
   ```

   * 将全部对象从缓存中删除

   ```java
   handle.clear();
   ```

   * 将redis里面的对象全量同步到本地缓存（一般用于定时兜底策略）

   ```java
   handle.sync();
   ```





