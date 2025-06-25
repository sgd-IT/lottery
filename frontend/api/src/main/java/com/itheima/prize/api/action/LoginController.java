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
        /**
         * 1、密码错误5次的校验
         *   1）从redis中获取用户名密码错误次数(redisUtil.get())
         * 2、用户名密码校验
         *   1）从数据库中查询用户名密码
         *      使用MP的语法查询用户(userService.list(wrapper);)
         * 3、如果用户不为空，保存会话并返回登录信息
         *   1）保存用户信息到session中(request.getSession().setAttribute())
         *   2）返回登录信息user
         * 4、如果用户为空，返回错误信息，并记录错误次数
         *   1）记录错误次数(redisUtil.incr(),redisUtil.expire())
         *   2）返回错误信息
         */
        return null;
    }

    @GetMapping("/logout")
    @ApiOperation(value = "退出")
    public ApiResult logout(HttpServletRequest request) {
        //TODO：任务3.2-登录模块-用户退出
        /**
         * 1、如果session不为空，直接删除session(session.invalidate();)
         * 2、如果session为空，直接返回成功
         */
        return null;
    }

}