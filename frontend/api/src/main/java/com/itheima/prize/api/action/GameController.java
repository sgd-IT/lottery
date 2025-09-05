package com.itheima.prize.api.action;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.itheima.prize.commons.db.entity.CardGame;
import com.itheima.prize.commons.db.entity.CardProductDto;
import com.itheima.prize.commons.db.entity.ViewCardUserHit;
import com.itheima.prize.commons.db.service.CardGameService;
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

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping(value = "/api/game")
@Api(tags = {"活动模块"})
public class GameController {
    @Autowired
    private GameLoadService loadService;
    @Autowired
    private CardGameService gameService;
    @Autowired
    private ViewCardUserHitService hitService;
    @Autowired
    private RedisUtil redisUtil;

    @GetMapping("/list/{status}/{curpage}/{limit}")
    @ApiOperation(value = "活动列表")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "status", value = "活动状态（-1=全部，0=未开始，1=进行中，2=已结束）", example = "-1", required = true),
            @ApiImplicitParam(name = "curpage", value = "第几页", defaultValue = "1", dataType = "int", example = "1", required = true),
            @ApiImplicitParam(name = "limit", value = "每页条数", defaultValue = "10", dataType = "int", example = "3", required = true)
    })
    public ApiResult list(@PathVariable int status, @PathVariable int curpage, @PathVariable int limit) {
        //TODO：任务4.1-活动模块-活动列表
        /**
         * 分页查询活动列表
         * @param curpage 当前页码
         * @param limit 每页显示记录数
         * @param status 活动状态筛选条件
         * @return ApiResult 包含分页数据的API响应结果
         */

        // 获取当前时间用于判断活动状态
        Date now = new Date();
        // 构建查询条件
        QueryWrapper<CardGame> wrapper = new QueryWrapper<>();

        // 根据不同状态添加查询条件
        if (status == 0) {
            // 未开始：starttime > now
            wrapper.gt("starttime", now);
        } else if (status == 1) {
            // 进行中：starttime <= now 且 endtime > now
            wrapper.le("starttime", now).gt("endtime", now);
        } else if (status == 2) {
            // 已结束：endtime <= now
            wrapper.le("endtime", now);
        }
        // 当status == -1时，不添加任何条件，查询所有活动

        // 从数据库分页查询活动列表
        Page<CardGame> page = gameService.page(new Page<>(curpage, limit), wrapper);

        // 将Page对象转换为PageBean对象
        PageBean<CardGame> pageBean = new PageBean<>(page);

        // 构造并返回API结果
        return new ApiResult(1, "成功", pageBean);
    }


    @GetMapping("/info/{gameid}")
    @ApiOperation(value = "活动信息")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "gameid", value = "活动id", example = "1", required = true)
    })
    public ApiResult<CardGame> info(@PathVariable int gameid) {
        //TODO：任务4.2-活动模块-活的信息


        // 查询活动信息
        CardGame gameInfo = gameService.getById(gameid);
        // 增加状态判断逻辑
        Date now = new Date();
        if (now.before(gameInfo.getStarttime())) {
            // 活动未开始
            gameInfo.setStatus(0);
        } else if (now.after(gameInfo.getEndtime())) {
            // 活动已结束，但系统只支持0和1，这里设置为1但前端可能根据时间判断为已结束
            gameInfo.setStatus(1);
        } else {
            // 活动进行中
            gameInfo.setStatus(1);
        }

        return new ApiResult<>(1, "成功", gameInfo);
    }

    @GetMapping("/products/{gameid}")
    @ApiOperation(value = "奖品信息")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "gameid", value = "活动id", example = "1", required = true)
    })
    public ApiResult<List<CardProductDto>> products(@PathVariable int gameid) {
        //TODO：任务4.3-活动模块-奖品信息

        // 查询活动奖品信息
        List<CardProductDto> productList = loadService.getByGameId(gameid);
        return new ApiResult<>(1, "成功", productList);
    }

    @GetMapping("/hit/{gameid}/{curpage}/{limit}")
    @ApiOperation(value = "中奖列表")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "gameid", value = "活动id", dataType = "int", example = "1", required = true),
            @ApiImplicitParam(name = "curpage", value = "第几页", defaultValue = "1", dataType = "int", example = "1", required = true),
            @ApiImplicitParam(name = "limit", value = "每页条数", defaultValue = "10", dataType = "int", example = "3", required = true)
    })
    public ApiResult<PageBean<ViewCardUserHit>> hit(@PathVariable int gameid, @PathVariable int curpage, @PathVariable int limit) {
        //TODO：任务4.4-活动模块-中奖列表


        // 查询中奖列表
        Page<ViewCardUserHit> page = hitService.page(new Page<>(curpage, limit),
                new QueryWrapper<ViewCardUserHit>().eq("gameid", gameid).orderByDesc("hittime"));
        PageBean<ViewCardUserHit> pageBean = new PageBean<>(page);
        return new ApiResult<>(1, "成功", pageBean);
    }


}