package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    protected ISeckillVoucherService seckillVoucherService;
    @Autowired
    protected IVoucherOrderService voucherOrderService;
    @Autowired
    protected RedisIdWorker redisIdWorker;

    @Autowired
    protected SimpleRedisLock simpleRedisLock;

    @Autowired
    protected RedissonClient redissonClient;
    @Autowired
    protected StringRedisTemplate stringRedisTemplate;
    private IVoucherOrderService proxy;
    private BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue<>(1024*1024);
    private static DefaultRedisScript<Long> SECKILL_SCRIPT = null;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("secKill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(()->{
            while (true){
                VoucherOrder voucherOrder = orderTask.take();
                handleVoucherOrder(voucherOrder);
            }
        });
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock(RedisConstants.REDISSON_LOCK + userId);
        if (!lock.tryLock()){
            log.error("不允许重复创建voucherOrder");
            return;
        }
        proxy.createVoucherOrder(voucherOrder);
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        //流程==》  Redis优化秒杀
        Long userId = UserHolder.getUser().getId();
        Long res = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        int r = res.intValue();
        if (r != 0){
            return Result.fail(r == 1?"无库存":"重复下单");
        }
        long orderId = redisIdWorker.nextId("order");
        //有购买资格 将下单信息保存到阻塞队列中
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTask.add(voucherOrder);
        //获取代理对象
        proxy  = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    //    @Override
//    public Result seckillVoucher(Long voucherId) {
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀优惠卷时间尚未开始");
//        }
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀优惠卷时间已经结束");
//        }
//        if (seckillVoucher.getStock() < 1){
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//
////        boolean isLock = simpleRedisLock.tryLock("order:" + userId, 1200);
//        RLock lock = redissonClient.getLock(RedisConstants.REDISSON_LOCK + userId);
//        boolean isLock = lock.tryLock();
//        if (!isLock){
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
////            simpleRedisLock.unlock("order:" + userId);
//            lock.unlock();
//        }
//    }
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherOrder);
        Long userId = UserHolder.getUser().getId();
        Long voucherId = voucherOrder.getVoucherId();
        LambdaQueryWrapper<VoucherOrder> voucherOrderLambdaQueryWrapper = new LambdaQueryWrapper<>();
        voucherOrderLambdaQueryWrapper
                .eq(VoucherOrder::getVoucherId, voucherId)
                .eq(VoucherOrder::getUserId, userId);
        int count = voucherOrderService.count(voucherOrderLambdaQueryWrapper);
        if (count > 0) {
            log.error("用户已购买");
        }
        LambdaUpdateWrapper<SeckillVoucher> seckillVoucherLambdaUpdateWrapper = new LambdaUpdateWrapper<>();
        seckillVoucherLambdaUpdateWrapper
                .set(SeckillVoucher::getStock, seckillVoucher.getStock() - 1)
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .gt(SeckillVoucher::getStock, 0);
        boolean update = seckillVoucherService.update(seckillVoucherLambdaUpdateWrapper);
        if (!update) {
            log.error("库存不足");
        }
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long order = redisIdWorker.nextId("order");
//        voucherOrder.setId(order);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherOrder);
        save(voucherOrder);
//        return Result.ok(voucherOrder);
    }
}
