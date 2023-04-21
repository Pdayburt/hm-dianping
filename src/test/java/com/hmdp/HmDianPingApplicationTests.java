package com.hmdp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private ShopServiceImpl shopService;
    @Test
    void testShopServiceImpl() throws InterruptedException {
        shopService.saveShop2Redis(1L,10L);
    }

    @Autowired
    private RedisIdWorker redisIdWorker;
    private ExecutorService es = Executors.newFixedThreadPool(500);
    @Test
    void testRedisId() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
//        long BEGIN = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(()->{
                for (int j = 0; j < 100; j++) {
                    long order = redisIdWorker.nextId("order");
                    System.out.println("id="+order);
                }
                countDownLatch.countDown();
            });
        }
        countDownLatch.await();
//        long END = System.currentTimeMillis();
//        System.out.println("time = "+(END - BEGIN));

    }

    @Test
    void testRedis() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(3000);
        for (int i = 0; i < 3000; i++) {
            es.submit(()->{
                for (int i1 = 0; i1 < 100; i1++) {
                    long purchase = redisIdWorker.nextId("purchase");
                    System.out.println("id = "+purchase);
                }
                countDownLatch.countDown();
            });
        }
        countDownLatch.await();
    }

    @Test
    void testArray(){
        System.out.println(3 * 0.1);
    }





}
