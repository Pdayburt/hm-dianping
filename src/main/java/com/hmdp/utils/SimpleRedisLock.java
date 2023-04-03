package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;


@Component
public class SimpleRedisLock implements ILock{
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT ;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    @Override
    public boolean tryLock(String name,long timeOutSec) {
        String id =ID_PREFIX+ Thread.currentThread().getId();
        ValueOperations<String, String> valueOperations =
                stringRedisTemplate.opsForValue();
        Boolean aBoolean = valueOperations.setIfAbsent(RedisConstants.LOCK_PREFIX+name,
                id, timeOutSec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }

    @Override
    public void unlock(String name) {
        Long execute = stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(RedisConstants.LOCK_PREFIX + name)
                , ID_PREFIX + Thread.currentThread().getId());
    }
//    @Override
//    public void unlock(String name) {
//        ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();
//        String id = valueOperations.get(RedisConstants.LOCK_PREFIX + name);
//        String threadId =ID_PREFIX+ Thread.currentThread().getId();
//        if (threadId.equals(id)){
//            stringRedisTemplate.delete(RedisConstants.LOCK_PREFIX+name);
//        }
//    }
}
