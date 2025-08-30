package com.itheima.prize.api.action;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.itheima.prize.commons.config.RedisKeys;
import com.itheima.prize.commons.db.entity.CardUser;
import com.itheima.prize.commons.db.mapper.CardUserMapper;
import com.itheima.prize.commons.db.service.CardUserService;
import com.itheima.prize.commons.utils.ApiResult;
import com.itheima.prize.commons.utils.PasswordUtil;
import com.itheima.prize.commons.utils.RedisUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import jdk.vm.ci.meta.Constant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.List;

@RestController
@RequestMapping(value = "/api")
@Api(tags = {"登录模块"})
public class LoginController {
    @Autowired
    private CardUserService userService;

    @Autowired
    private RedisUtil redisUtil;

    @PostMapping("/login")
    @ApiOperation(value = "登录")
    @ApiImplicitParams({
            @ApiImplicitParam(name="account",value = "用户名",required = true),
            @ApiImplicitParam(name="password",value = "密码",required = true)
    })
    public ApiResult login(HttpServletRequest request, @RequestParam String account,@RequestParam String password) {
        //TODO：任务3.1-登录模块-用户登录
        //1、密码错误5次的校验
        Long times = (Long) redisUtil.get(RedisKeys.USERLOGINTIMES+account);
        if(times!=null&&times>=5){
            return new ApiResult(0,"密码错误5次，请5分钟后再登录",null);
        }
        //2、用户名密码校验
        QueryWrapper<CardUser> wrapper = new QueryWrapper<>();
        wrapper.eq("user_name",account);
        //密码进行md5加密再去与数据库进行对比
        String md5Pwd = PasswordUtil.encodePassword(password);
        wrapper.eq("password",md5Pwd);
        List<CardUser> list = userService.list(wrapper);
        //3、如果用户不为空，保存会话并返回登录信息
        if(!list.isEmpty()){
            redisUtil.del(RedisKeys.USERLOGINTIMES+account);
            request.getSession().setAttribute("user",list.get(0));
            CardUser user = list.get(0);
            return new ApiResult(1,"登录成功",user);
        }
        //4、如果用户为空，返回错误信息，并记录错误次数
        else{
            redisUtil.incr(RedisKeys.USERLOGINTIMES+account,1);
            redisUtil.expire(RedisKeys.USERLOGINTIMES+account,60*5);
            return new ApiResult(0,"账户名或密码错误",null);
        }
    }

    @GetMapping("/logout")
    @ApiOperation(value = "退出")
    public ApiResult logout(HttpServletRequest request) {
        //TODO：任务3.2-登录模块-用户退出

        //1、如果session不为空，直接删除session(session.invalidate();)
        HttpSession session = request.getSession();
        if (session != null) {
            session.invalidate();
        }
        //2、如果session为空，直接返回成功
        return new ApiResult(1,"退出成功",null);
    }

}