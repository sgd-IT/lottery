package com.itheima.prize.api.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

@Service
public class LuaScript {
    @Autowired
    private RedisTemplate redisTemplate;
 
    private DefaultRedisScript<Long> script;
 
    @PostConstruct
    public void init(){
        script = new DefaultRedisScript<Long>();
        script.setResultType(Long.class);
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/tokenCheck.lua")));
    }

    /*
    * 调lua脚本获取token
    * gameId: 活动id， userId：当前登录用户的id， maxCount：当前活动允许的最大中奖次数
    * */
    public Long tokenCheck(int gameId,int userId,int maxCount){

        List<String> keys = new ArrayList();
        keys.add(String.valueOf(gameId));
        keys.add(String.valueOf(userId));
        keys.add(String.valueOf(maxCount));

        Long result = (Long) redisTemplate.execute(script,keys,0,0);

        return result;
    }
}