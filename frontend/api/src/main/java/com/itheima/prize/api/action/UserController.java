package com.itheima.prize.api.action;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.itheima.prize.commons.config.RedisKeys;
import com.itheima.prize.commons.db.entity.CardUser;
import com.itheima.prize.commons.db.entity.CardUserDto;
import com.itheima.prize.commons.db.entity.ViewCardUserHit;
import com.itheima.prize.commons.db.mapper.ViewCardUserHitMapper;
import com.itheima.prize.commons.db.service.GameLoadService;
import com.itheima.prize.commons.db.service.ViewCardUserHitService;
import com.itheima.prize.commons.utils.ApiResult;
import com.itheima.prize.commons.utils.PageBean;
import com.itheima.prize.commons.utils.RedisUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

@RestController
@RequestMapping(value = "/api/user")
@Api(tags = {"用户模块"})
public class UserController {

    @Autowired
    private RedisUtil redisUtil;
    @Autowired
    private ViewCardUserHitService hitService;
    @Autowired
    private GameLoadService loadService;

    @GetMapping("/info")
    @ApiOperation(value = "用户信息")
    public ApiResult info(HttpServletRequest request) {
        //TODO：任务3.3-用户模块-用户信息
        /**
         * 1、从session中获取用户信息
         * 2、获取games和products属性值。获取方法：loadService.getGamesNumByUserId(userid)、loadService.getPrizesNumByUserId(userid)
         */
        HttpSession session = request.getSession();
        CardUser user = (CardUser) session.getAttribute("user");

        // 添加登录状态检查
        if (user == null) {
            return new ApiResult(0, "用户未登录或登录已超时", null);
        }
        // 创建用户信息传输对象并设置基本信息
        CardUserDto userdto = new CardUserDto();
        userdto.setId(user.getId());
        userdto.setUname(user.getUname());
        userdto.setPic(user.getPic());
        userdto.setRealname(user.getRealname());
        userdto.setPhone(user.getPhone());
        userdto.setLevel(user.getLevel());

        // 获取用户的游戏数量和奖品数量并设置到用户信息传输对象中
        userdto.setGames(loadService.getGamesNumByUserId(user.getId()));
        userdto.setProducts(loadService.getPrizesNumByUserId(user.getId()));

        // 返回包含用户信息的成功结果
        return new ApiResult(1, "成功", userdto);

    }

    @GetMapping("/hit/{gameid}/{curpage}/{limit}")
    @ApiOperation(value = "我的奖品")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "gameid", value = "活动id（-1=全部）", dataType = "int", example = "1", required = true),
            @ApiImplicitParam(name = "curpage", value = "第几页", defaultValue = "1", dataType = "int", example = "1"),
            @ApiImplicitParam(name = "limit", value = "每页条数", defaultValue = "10", dataType = "int", example = "3")
    })
    public ApiResult hit(@PathVariable int gameid, @PathVariable int curpage, @PathVariable int limit, HttpServletRequest request) {
        //TODO：任务3.4-用户模块-我的奖品
        /**
         * hitService.page(new Page<>(curpage,limit),new QueryWrapper<ViewCardUserHit>().eq("userid",userid).eq("gameid",gameid))
         */
        /**
         * 获取用户在指定游戏中的点击记录分页数据
         * @param request HTTP请求对象，用于获取用户会话信息
         * @param curpage 当前页码
         * @param limit 每页显示记录数
         * @param gameid 游戏ID
         * @return ApiResult 包含分页数据的API响应结果
         */
        // 获取用户ID
        HttpSession session = request.getSession();
        CardUser user = (CardUser) session.getAttribute("user");
        if (user == null) {
            return new ApiResult(0, "用户未登录或登录已超时", null);
        }

        int userid = user.getId();
        // 根据用户ID和游戏ID查询点击记录并进行分页处理
        Page page = hitService.page(new Page<>(curpage, limit), new QueryWrapper<ViewCardUserHit>().eq("userid", userid).eq("gameid", gameid));
        PageBean<ViewCardUserHit> pageBean = new PageBean<>(page);
        return new ApiResult(1, "成功", pageBean);
    }

}