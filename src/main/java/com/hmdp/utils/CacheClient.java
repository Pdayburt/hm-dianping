package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public void setExpire(String key, Object value, Long second, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),second,timeUnit);
    }
    public void setLogicExpire(String key,Object value,Long time,TimeUnit timeUnit){
        RedisData data = new RedisData();
        data.setData(value);
        data.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(data));
    }

    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,
            Long second,TimeUnit timeUnit){
        String key = keyPrefix+id;
        ValueOperations<String, String> valueOperations =
                stringRedisTemplate.opsForValue();
        String json = valueOperations.get(key);
        if (StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json,type);
        }
        if (json != null){
            return null;
        }
        R r = dbFallback.apply(id);
        if (r == null){
            valueOperations.set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        this.setExpire(key,JSONUtil.toJsonStr(r),second,timeUnit);
        return r;
    }

    public <R,ID> R queryWithLogicExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback ,
                                         Long second,TimeUnit timeUnit){
        String key = keyPrefix+id;
        ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();
        String json = valueOperations.get(key);
        if (StrUtil.isBlank(json)){
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        String lockKey = RedisConstants.LOCK_SHOP_KEY+id;
        if (tryLock(lockKey)){
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    R apply = dbFallback.apply(id);
                    this.setLogicExpire(key,apply,second,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return r;
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    private boolean tryLock(String  key){
        ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();
        Boolean aBoolean = valueOperations.setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }

    private boolean unlock(String key){
        Boolean delete = stringRedisTemplate.delete(key);
        return BooleanUtil.isTrue(delete);
    }

}
