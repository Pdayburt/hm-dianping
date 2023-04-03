package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    public static final long BEGIN_TIMESTAMP = 1672531200L;
    /**
     * 序列号位数
     */
    public static final int COUNT_BITS = 32;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    public long nextId(String keyPrefix){
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;
        //生成序列号
        ValueOperations<String, String> valueOperations =
                stringRedisTemplate.opsForValue();
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = valueOperations.increment("inc:" + keyPrefix + ":" + date);
        return timeStamp<<COUNT_BITS | count;
    }

}
