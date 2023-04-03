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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
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
    @Override

    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀优惠卷时间尚未开始");
        }
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀优惠卷时间已经结束");
        }
        if (seckillVoucher.getStock() < 1){
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();

//        boolean isLock = simpleRedisLock.tryLock("order:" + userId, 1200);
        RLock lock = redissonClient.getLock(RedisConstants.REDISSON_LOCK + userId);
        boolean isLock = lock.tryLock();
        if (!isLock){
            return Result.fail("不允许重复下单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            simpleRedisLock.unlock("order:" + userId);
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        Long userId = UserHolder.getUser().getId();
        LambdaQueryWrapper<VoucherOrder> voucherOrderLambdaQueryWrapper = new LambdaQueryWrapper<>();
        voucherOrderLambdaQueryWrapper
                .eq(VoucherOrder::getVoucherId, voucherId)
                .eq(VoucherOrder::getUserId, userId);
        int count = voucherOrderService.count(voucherOrderLambdaQueryWrapper);
        if (count > 0) {
            return Result.fail("用户已购买");
        }
        LambdaUpdateWrapper<SeckillVoucher> seckillVoucherLambdaUpdateWrapper = new LambdaUpdateWrapper<>();
        seckillVoucherLambdaUpdateWrapper
                .set(SeckillVoucher::getStock, seckillVoucher.getStock() - 1)
                .eq(SeckillVoucher::getVoucherId, voucherId)
                .gt(SeckillVoucher::getStock, 0);
        boolean update = seckillVoucherService.update(seckillVoucherLambdaUpdateWrapper);
        if (!update) {
            return Result.fail("库存不足");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        long order = redisIdWorker.nextId("order");
        voucherOrder.setId(order);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(voucherOrder);
    }
}
