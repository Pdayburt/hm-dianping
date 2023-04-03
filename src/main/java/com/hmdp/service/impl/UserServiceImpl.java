package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone) {
        if (RegexUtils.isPhoneInvalid(phone)) return Result.fail("手机号格式错误");
        String code = RandomUtil.randomNumbers(6);
        ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();
        valueOperations.set(RedisConstants.LOGIN_CODE_KEY +phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.debug("验证码发送成功:{},有效期为:{}分钟",code,RedisConstants.LOGIN_CODE_TTL);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) return Result.fail("手机格式不正确");

        ValueOperations<String, String> valueOperations = stringRedisTemplate.opsForValue();
        String cacheCode = valueOperations.get(RedisConstants.LOGIN_CODE_KEY +phone);
        String code = loginForm.getCode();

        if (code == null || !cacheCode.equals(code)) return Result.fail("验证码错误!");
        LambdaQueryWrapper<User> userLambdaQueryWrapper = new LambdaQueryWrapper<>();
        LambdaQueryWrapper<User> eq = userLambdaQueryWrapper.eq(User::getPhone, loginForm.getPhone());
        User user = getOne(eq);
        if (user == null){
            user=createAndSaveUserWithPhone(loginForm.getPhone());
        }
        String token = UUID.randomUUID().toString();
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String tokenKey = RedisConstants.LOGIN_USER_KEY+token;
        HashOperations<String, Object, Object> hashOperations =
                stringRedisTemplate.opsForHash();
        Map<String, Object> usrDtoMap = BeanUtil.
                beanToMap(userDTO,new HashMap<>(),
                        CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        hashOperations.putAll(tokenKey,usrDtoMap);
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);
        log.info("tokenKey======>"+token);
        return Result.ok(tokenKey);
    }

    private User createAndSaveUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
