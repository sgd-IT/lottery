package com.itheima.prize.api.action;


import com.itheima.prize.api.config.LuaScript;
import com.itheima.prize.commons.config.RedisKeys;
import com.itheima.prize.commons.db.entity.CardGame;
import com.itheima.prize.commons.db.entity.CardUser;
import com.itheima.prize.commons.utils.ApiResult;
import com.itheima.prize.commons.utils.RedisUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.text.SimpleDateFormat;
import java.util.*;

@RestController
@RequestMapping("/api/act")
@Api(tags = {"抽奖模块"})
public class ActController {
    private final static Logger log = LoggerFactory.getLogger(ActController.class);
    @Autowired
    private RedisUtil redisUtil;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private LuaScript luaScript;

    @GetMapping("/limits/{gameid}")
    @ApiOperation(value = "剩余次数")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "gameid", value = "活动id", example = "1", required = true)
    })
    public ApiResult<Object> limits(@PathVariable int gameid, HttpServletRequest request) {
        //获取活动基本信息
        CardGame game = (CardGame) redisUtil.get(RedisKeys.INFO + gameid);
        if (game == null) {
            return new ApiResult<>(-1, "活动未加载", null);
        }
        //获取当前用户
        HttpSession session = request.getSession();
        CardUser user = (CardUser) session.getAttribute("user");
        if (user == null) {
            return new ApiResult(-1, "未登陆", null);
        }
        //用户可抽奖次数
        Integer enter = (Integer) redisUtil.get(RedisKeys.USERENTER + gameid + "_" + user.getId());
        if (enter == null) {
            enter = 0;
        }
        //根据会员等级，获取本活动允许的最大抽奖次数
        Integer maxenter = (Integer) redisUtil.hget(RedisKeys.MAXENTER + gameid, user.getLevel() + "");
        //如果没设置，默认为0，即：不限制次数
        maxenter = maxenter == null ? 0 : maxenter;

        //用户已中奖次数
        Integer count = (Integer) redisUtil.get(RedisKeys.USERHIT + gameid + "_" + user.getId());
        if (count == null) {
            count = 0;
        }
        //根据会员等级，获取本活动允许的最大中奖数
        Integer maxcount = (Integer) redisUtil.hget(RedisKeys.MAXGOAL + gameid, user.getLevel() + "");
        //如果没设置，默认为0，即：不限制次数
        maxcount = maxcount == null ? 0 : maxcount;

        //幸运转盘类，先给用户随机剔除，再获取令牌，有就中，没有就说明抢光了
        //一般这种情况会设置足够的商品，卡在随机上
        Integer randomRate = (Integer) redisUtil.hget(RedisKeys.RANDOMRATE + gameid, user.getLevel() + "");
        if (randomRate == null) {
            randomRate = 100;
        }

        Map map = new HashMap();
        map.put("maxenter", maxenter);
        map.put("enter", enter);
        map.put("maxcount", maxcount);
        map.put("count", count);
        map.put("randomRate", randomRate);

        return new ApiResult<>(1, "成功", map);
    }

    @GetMapping("/go/{gameid}")
    @ApiOperation(value = "抽奖")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "gameid", value = "活动id", example = "1", required = true)
    })
    public ApiResult<Object> act(@PathVariable int gameid, HttpServletRequest request) {
        //TODO：任务6.1-抽奖业务-抽奖接口

        log.info("调用抽奖接口，传入的 gameid = {}", gameid);

        //抽奖开始时，从令牌队列左侧获取令牌
        HttpSession session = request.getSession();
        CardUser user = (CardUser) session.getAttribute("user");
        //获取活动基本信息
        CardGame game = (CardGame) redisUtil.get(RedisKeys.INFO + gameid);
        if (game == null) {
            return new ApiResult<>(-1, "活动未加载", null);
        }

        // 根据会员等级，获取本活动允许的最大抽奖次数（活动规则，不修改）
        Integer maxEnter = (Integer) redisUtil.hget(RedisKeys.MAXENTER + gameid, user.getLevel() + "");
        //如果没设置，默认为0，即：不限制次数
        maxEnter = maxEnter == null ? 0 : maxEnter;

        // 调用Lua脚本进行抽奖逻辑处理
        log.info("调用Lua脚本进行抽奖逻辑处理，传入的 gameid = {}, userId = {}, maxEnter = {}", gameid, user.getId(), maxEnter);
        Long token = luaScript.tokenCheck(gameid, user.getId(), maxEnter);
        // 根据Lua脚本返回值处理结果
        if (token == -1L) {
            // 用户中奖次数已达上限
            return new ApiResult<>(-1, "抽奖次数已用完", null);
        } else if (token == -2L) {
            // 奖品已抽完
            return new ApiResult<>(-1, "奖品已抽完", null);
        } else if (token == 0L) {
            // 未中奖情况：发送参与消息到RabbitMQ
            Map<String, Object> playMessage = new HashMap<>();
            playMessage.put("userId", user.getId());
            playMessage.put("gameId", gameid);
            playMessage.put("timestamp", System.currentTimeMillis());
            rabbitTemplate.convertAndSend("prize_queue_play", playMessage);

            // 构造返回结果
            Map<String, Object> result = new HashMap<>();
            result.put("hit", false);
            result.put("token", token);

            return new ApiResult<>(1, "很遗憾，未中奖", result);
        } else {
            // 中奖情况：获取奖品信息
            String tokenKey = RedisKeys.TOKEN + gameid + "_" + token;
            Object prize = redisUtil.get(tokenKey);

            if (prize != null) {
                // 发送中奖消息到RabbitMQ
                Map<String, Object> hitMessage = new HashMap<>();
                hitMessage.put("userId", user.getId());
                hitMessage.put("gameId", gameid);
                hitMessage.put("prize", prize);
                hitMessage.put("timestamp", System.currentTimeMillis());
                rabbitTemplate.convertAndSend("prize_queue_hit", hitMessage);

                // 构造返回结果
                Map<String, Object> result = new HashMap<>();
                result.put("hit", true);
                result.put("prize", prize);
                result.put("token", token);

                return new ApiResult<>(1, "恭喜中奖！", result);
            } else {
                // 理论上不应该出现的情况
                return new ApiResult<>(-1, "奖品信息异常", null);
            }
        }
    }

    /**
     * 获取指定活动的缓存信息
     *
     * @param gameid 活动ID
     * @return 返回包含活动基本信息和令牌桶信息的ApiResult对象
     */
    @GetMapping("/info/{gameid}")
    @ApiOperation(value = "缓存信息")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "gameid", value = "活动id", example = "1", required = true)
    })
    public ApiResult info(@PathVariable int gameid) {
        // 创建Map存储所有缓存信息
        Map<String, Object> cacheInfo = new HashMap<>();

        // 1. 活动基本信息
        CardGame game = (CardGame) redisUtil.get(RedisKeys.INFO + gameid);
        cacheInfo.put("game_info_" + gameid, game);

        // 2. 令牌桶及奖品信息
        String tokensKey = RedisKeys.TOKENS + gameid;
        Map<String, Object> tokensMap = new HashMap<>();

        if (redisUtil.hasKey(tokensKey)) {
            // 获取Redis中存储的所有令牌列表
            List<Object> redisTokens = redisUtil.lrange(tokensKey, 0, -1);
            // 将Redis中的令牌添加到本地令牌桶中
            if (redisTokens != null) {
                for (Object tokenObj : redisTokens) {
                    String token = String.valueOf(tokenObj);
                    // 获取令牌对应的奖品信息
                    Object prize = redisUtil.get(RedisKeys.TOKEN + gameid + "_" + token);
                    if (prize != null) {
                        try {
                            // 将时间戳转换为可读的日期格式
                            long timestamp = Long.parseLong(token);
                            Date tokenDate = new Date(timestamp / 1000); // 转换为毫秒
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                            String formattedToken = sdf.format(tokenDate);
                            tokensMap.put(formattedToken, prize);
                        } catch (NumberFormatException e) {
                            // 如果转换失败，跳过该令牌
                            continue;
                        }
                    }
                }
            }
        }


        // 3. 活动最大中奖次数规则
        Map<Object, Object> maxGoalMap = redisUtil.hmget(RedisKeys.MAXGOAL + gameid);
        if (maxGoalMap != null) {
            cacheInfo.put("game_maxgoal_" + gameid, maxGoalMap);
        }

        // 4. 活动最大参与次数规则
        Map<Object, Object> maxEnterMap = redisUtil.hmget(RedisKeys.MAXENTER + gameid);
        if (maxEnterMap != null) {
            cacheInfo.put("game_maxenter_" + gameid, maxEnterMap);
        }

        cacheInfo.put("game_tokens_" + gameid, tokensMap);
        return new ApiResult(1, "缓存信息", cacheInfo);
    }


}
