package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
//        Shop shop = queryWithMutex(id);
//        Shop shop = queryWithLogicExpire(id);

//        Shop shop = cacheClient.queryWithPassThrough(
//                RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
//                this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        Shop shop = cacheClient.queryWithLogicExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null){
            return Result.fail("店铺不存在....");
        }
        return Result.ok(shop);
    }

    public Shop queryWithLogicExpire(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY+id;
        ValueOperations<String, String> valueOperations =
                stringRedisTemplate.opsForValue();
        String redisData = valueOperations.get(key);
        if (StrUtil.isBlank(redisData)){
            return null;
        }
        RedisData data = JSONUtil.toBean(redisData, RedisData.class);
        JSONObject jsonObject = (JSONObject) data.getData();
        Shop shop = JSONUtil.toBean(jsonObject, Shop.class);
        LocalDateTime expireTime = data.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())){
            //未过期
            return shop;
        }
        //已过期需要重建缓存
        String lockKey = RedisConstants.LOCK_SHOP_KEY+id;
        if (tryLock(lockKey)){
            String redisJson = valueOperations.get(key);
            RedisData data1 = JSONUtil.toBean(redisJson, RedisData.class);
            LocalDateTime expireTime1 = data1.getExpireTime();
            if (expireTime1.isBefore(LocalDateTime.now())){
                CACHE_REBUILD_EXECUTOR.submit(()->{
                    //重建缓存
                    try {
                        this.saveShop2Redis(id,20L);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        unlock(lockKey);
                    }

                });
            }
        }
        return shop;
    }

    public Shop queryWithMutex(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY+id;
        ValueOperations<String, String> valueOperations =
                stringRedisTemplate.opsForValue();
        String shopJson = valueOperations.get(key);
        Shop shop = null;
        String lockKey = RedisConstants.LOCK_SHOP_KEY+id;
        try {
            if (StrUtil.isNotBlank(shopJson)){
                return JSONUtil.toBean(shopJson,Shop.class);
            }
            if (shopJson != null){
                return null;
            }
            if (!tryLock(lockKey)){
                Thread.sleep(50);
                queryWithMutex(id);
            }
            shopJson = valueOperations.get(key);
            if (StrUtil.isNotBlank(shopJson)){
                return BeanUtil.toBean(shopJson, Shop.class);
            }
            shop = getById(id);
            Thread.sleep(200);
            if (shop == null){
                valueOperations.set(key,"",
                        RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            valueOperations.set(key,JSONUtil.toJsonStr(shop),
                    RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
           
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        return shop;
    }

    public Shop queryWithPassThrough(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY+id;
        ValueOperations<String, String> valueOperations =
                stringRedisTemplate.opsForValue();
        String shopJson = valueOperations.get(key);
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson !=null){
            return null;
        }
        Shop shop = getById(id);
        if (shop == null){
            valueOperations.set(key,"",
                    RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        valueOperations.set(key,JSONUtil.toJsonStr(shop),
                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    public void saveShop2Redis(Long id,Long expirationSeconds) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expirationSeconds));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
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
    @Transactional
    @Override
    public Result update(Shop shop) {
        if (shop.getId() ==null){
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }
}
