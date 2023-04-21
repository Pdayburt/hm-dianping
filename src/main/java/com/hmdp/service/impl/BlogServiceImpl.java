package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.*;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Autowired
    protected UserMapper userMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IFollowService followService;
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("blog不存在");
        }
        Long userId = blog.getUserId();
        User user = userMapper.selectById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        isBlogLiked(blog);
        return Result.ok(blog);
    }
    private void isBlogLiked(Blog blog) {
        ZSetOperations<String, String> zSetOperations =
                stringRedisTemplate.opsForZSet();
        Long id = blog.getId();
        UserDTO user = UserHolder.getUser();
        //用户未登录则无需点赞
        if (user == null){
            return ;
        }
        Long userId = UserHolder.getUser().getId();
        String key = "blog:like:"+id;
        Double score = zSetOperations.score(key, userId.toString());
        blog.setIsLike(score != null);
    }
    @Override
    public Result likeBlog(Long id) {
        //1.判断是否点赞？从redis中的set集合判断
        Long userId = UserHolder.getUser().getId();

        ZSetOperations<String, String> zSetOperations
                = stringRedisTemplate.opsForZSet();
        String key = "blog:like:"+id;
        Double score = zSetOperations.score(key, userId);
        if (score == null){
            //没有 则可以点赞 数据库点赞数+1  + 保存用户到Redis的set集合中
            boolean isSuccess = update().setSql("liked = liked+1")
                    .eq("id", id).update();
            if (isSuccess){
                zSetOperations.add(key,
                        userId.toString(),System.currentTimeMillis());
            }
        }else {
            //已点赞？ 数据库点赞数-1  + 保存用户 从 Redis的set集合中移除
            boolean isSuccess = update().setSql("liked = liked-1").
                    eq("id", id).update();
            zSetOperations.remove(key,userId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //查询top5点赞的用户
        ZSetOperations<String, String> zSetOperations =
                stringRedisTemplate.opsForZSet();
        String key = RedisConstants.BLOG_LIKED_KEY+id;
        Set<String> top5 = zSetOperations.range(key, 0, 4);
        if (top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //解析出userId
        List<UserDTO> userDTOList = query()
                .in("id",ids).last("order by field(id,"+idStr+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //查出用户
        return Result.ok(userDTOList);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        //查询笔记所有者的粉丝
        if (!isSuccess){
            return Result.fail("新增笔记失败～～～");
        }
        //发送笔记id给所有粉丝 (从follow表中找follow_user_id就是被关注者的id)
        List<Follow> follows = followService.list(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getFollowUserId, user.getId()));
        follows.stream()
                .forEach(follow -> {
                    //粉丝id
                    Long userId = follow.getId();
                    //推送
                    String key = "feed:"+userId;
                    stringRedisTemplate.opsForZSet()
                            .add(key,blog.getId().toString(),System.currentTimeMillis());
                });
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //查询收件箱
        String key = RedisConstants.FEED_KEY+userId;
        ZSetOperations<String, String> zSetOperations
                = stringRedisTemplate.opsForZSet();
        Set<ZSetOperations.TypedTuple<String>> typedTuples
                = zSetOperations.reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        if (typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //解析数据blogId,score(时间戳),offset
        long minTime = 0;
        int os = 1;
        List<Long> ids = new ArrayList<>(typedTuples.size());
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            //获取id
            ids.add(Long.valueOf(Objects.requireNonNull(typedTuple.getValue())));
            //获取相同分数(时间戳)
            long time = typedTuple.getScore().longValue();
            if (time == minTime){
                os++;
            }else {
                minTime = time;
                os = 1;
            }
        }
        String idStr = StrUtil.join(",", ids);
        //根据blogId 查询blog 封装返回
        List<Blog> blogs = query().in("id", ids)
                .last("order by field(id," + idStr + ")").list();
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }
}
