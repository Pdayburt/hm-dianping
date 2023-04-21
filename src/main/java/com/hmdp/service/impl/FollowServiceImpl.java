package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IUserService userService;
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long id = UserHolder.getUser().getId();
        SetOperations<String, String> setOperations =
                stringRedisTemplate.opsForSet();
        String key = "follows:"+id;
        //根据isFollow判断是关注还是取关 关注 新增记录
        if (isFollow){
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(id);
            boolean isSuccess = save(follow);
            if (isSuccess){
                //把关注的用户id放入redis中的set集合
                setOperations.add(key,followUserId.toString());
            }
        }else {
            //取关删除记录
            boolean isSuccess = remove(new LambdaUpdateWrapper<Follow>()
                    .eq(Follow::getUserId, id)
                    .eq(Follow::getFollowUserId, followUserId));
            //从redis中删除相关记录
            if (isSuccess){
                setOperations.remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result followOrNot(Long followUserId) {
        //查询是否关注
        Long id = UserHolder.getUser().getId();
        int count = count(new LambdaUpdateWrapper<Follow>()
                .eq(Follow::getUserId, id)
                .eq(Follow::getFollowUserId, followUserId));
        return Result.ok(count > 0 );
    }

    @Override
    public Result followCommon(Long id) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follow:"+userId;
        //获取交集
        String key2 = "follows:"+id;
        SetOperations<String, String> setOperations =
                stringRedisTemplate.opsForSet();
        Set<String> intersect = setOperations.intersect(key, key2);
        if (intersect == null){
            return Result.ok(Collections.emptyList());
        }
        //得到的交集的id，需要解析
        List<Long> ids = intersect.stream()
                .map(Long::valueOf).collect(Collectors.toList());
        //查询用户
        List<UserDTO> userDTOS = userService.listByIds(ids)
                .stream()
                .map(user ->
                        BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
