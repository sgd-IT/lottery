package com.itheima.prize.msg;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.itheima.prize.commons.config.RedisKeys;
import com.itheima.prize.commons.db.entity.*;
import com.itheima.prize.commons.db.service.CardGameProductService;
import com.itheima.prize.commons.db.service.CardGameRulesService;
import com.itheima.prize.commons.db.service.CardGameService;
import com.itheima.prize.commons.db.service.GameLoadService;
import com.itheima.prize.commons.utils.RedisUtil;
import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


import java.util.*;

/**
 * 活动信息预热，每隔1分钟执行一次
 * 查找未来1分钟内（含），要开始的活动
 */
@Component
public class GameTask {
    private final static Logger log = LoggerFactory.getLogger(GameTask.class);
    @Autowired
    private CardGameService gameService;
    @Autowired
    private CardGameProductService gameProductService;
    @Autowired
    private CardGameRulesService gameRulesService;
    @Autowired
    private GameLoadService gameLoadService;
    @Autowired
    private RedisUtil redisUtil;

    @Scheduled(cron = "0 * * * * ?")
    public void execute() {
        Date now = new Date();
        System.out.printf("scheduled!" + now);
        log.info("开始执行定时任务，当前时间: {}", now);
        //TODO：任务5.1-缓存预热-调度写入缓存card_user

        // 1. 查找未来1分钟内（含），要开始的活动
        QueryWrapper<CardGame> wrapper = new QueryWrapper<>();
        wrapper.gt("starttime", now);
        wrapper.le("starttime", DateUtils.addMinutes(now, 180));
        List<CardGame> games = gameService.list(wrapper);
        log.info("查询到 {} 个活动需要预热", games.size());

        // 2. 遍历活动，预热缓存
        //把（开始时间 > 当前时间）&& （开始时间 <= 当前时间+1分钟）的活动及相关信息放入redis，完成预热
        for (CardGame game : games) {
            log.info("开始预热活动: ID={}, 标题={}", game.getId(), game.getTitle());
            // 清理旧缓存
            cleanupGameCache(game.getId());

            // 活动基本信息
            redisUtil.set(RedisKeys.INFO + game.getId(), game, -1);
            log.debug("活动 {} 基本信息已缓存", game.getId());

            //活动奖品信息
            List<CardGameProduct> products = gameProductService.list(new QueryWrapper<CardGameProduct>().eq("gameid", game.getId()));
            redisUtil.set(RedisKeys.INFO + "products" + game.getId(), products, -1);
            log.debug("活动 {} 奖品信息已缓存，共 {} 个奖品", game.getId(), products.size());

            // 活动策略
            QueryWrapper<CardGameRules> rulesWrapper = new QueryWrapper<>();
            rulesWrapper.eq("gameid", game.getId());
            List<CardGameRules> rules = gameRulesService.list(rulesWrapper);
            log.debug("活动 {} 规则数量: {}", game.getId(), rules.size());
            for (CardGameRules rule : rules) {
                redisUtil.hset(RedisKeys.MAXGOAL + game.getId(), rule.getUserlevel() + "", rule.getGoalTimes());
                redisUtil.hset(RedisKeys.MAXENTER + game.getId(), rule.getUserlevel() + "", rule.getEnterTimes());
                log.debug("活动 {} 级别 {} 规则已缓存", game.getId(), rule.getUserlevel());
            }

            //抽奖令牌桶
            log.info("开始为活动 {} 生成令牌桶", game.getId());
            TokenBucket(game, products);
            log.info("活动 {} 预热完成", game.getId());

        }
        log.info("定时任务执行完成");
    }

    private void TokenBucket(CardGame game, List<CardGameProduct> products) {
        log.debug("开始为活动 {} 生成令牌桶，奖品数量: {}", game.getId(), products.size());
        long startTime = game.getStarttime().getTime();
        long endTime = game.getEndtime().getTime();
        long runTime = endTime - startTime;

        List<String> tokenList = new ArrayList<>();

        //统计总奖品数量
        int totalProducts = 0;
        for (CardGameProduct product : products) {
            totalProducts += product.getAmount();
        }
        log.debug("活动 {} 总奖品数量: {}", game.getId(), totalProducts);
        //生成令牌
        Random random = new Random();
        Set<Long> tokenSet = new HashSet<>();

        // 生成唯一的时间戳令牌用于标识奖品
        // 通过添加随机因子和重复检查确保每个令牌的唯一性
        for (int i = 0; i < totalProducts; i++) {
            // 生成随机时间戳
            long rnd = startTime + Math.abs(random.nextLong()) % runTime;
            // 添加随机因子防止重复（防止时间段内奖品重复）
            long token = rnd * 1000 + Math.abs(random.nextInt(1000));

            // 检查并重新生成令牌直到获得唯一值
            while (tokenSet.contains(token) || token < 0) {
                rnd = startTime + Math.abs(random.nextLong()) % runTime;
                token = rnd * 1000 + Math.abs(random.nextInt(1000));
            }
            // 将token添加到token集合中，用于去重和快速查找
            tokenSet.add(token);
            // 将token转换为字符串并添加到token列表中，保持插入顺序
            tokenList.add(String.valueOf(token));
        }
        //排序令牌
        tokenList.sort(String::compareTo);
        log.debug("活动 {} 令牌生成完成，令牌数量: {}", game.getId(), tokenList.size());

        //奖品映射信息
        createPrizeMapping(game, products, tokenList);


        //判断当前令牌是否对应奖品
        log.debug("活动 {} 开始设置令牌中奖标记", game.getId());
        // 计算过期时间，确保为正数
        long currentTimeSeconds = System.currentTimeMillis() / 1000;
        long endTimeSeconds = game.getEndtime().getTime() / 1000;
        long expireTimeWin = endTimeSeconds - currentTimeSeconds + 3600;
        if (expireTimeWin <= 0) {
            expireTimeWin = 3600; // 默认1小时
        }
        for (String token : tokenList) {
            //将令牌与奖品关联
            redisUtil.set(RedisKeys.TOKEN + game.getId() + "_" + token + "_win",
                    true, expireTimeWin);
        }

        // 把令牌列表写入redis，将令牌放入Redis双端队列
        if (!tokenList.isEmpty()) {
            redisUtil.rightPushAll(RedisKeys.TOKENS + game.getId(), tokenList);
            log.info("活动 {} 令牌桶生成完成，令牌数量: {}", game.getId(), tokenList.size());
        }
    }


    /**
     * 奖品映射信息
     *
     * @param game
     * @param products
     * @param tokenList
     */
    private void createPrizeMapping(CardGame game, List<CardGameProduct> products, List<String> tokenList) {
        log.debug("开始为活动 {} 创建奖品映射", game.getId());
        //计算过期时间（活动结束时间+一定缓冲时间）
        long expireTime = game.getEndtime().getTime() / 1000 - System.currentTimeMillis() / 1000 + 3600; // 多加1小时缓冲
        int tokenIndex = 0;
        for (CardGameProduct product : products) {
            for (int i = 0; i < product.getAmount() && tokenIndex < tokenList.size(); i++) {
                String token = tokenList.get(tokenIndex++);
                // 以活动id_令牌为key，奖品信息为value
                redisUtil.set(RedisKeys.TOKEN + game.getId() + "_" + token, product, expireTime);
            }
        }
        log.debug("活动 {} 奖品映射创建完成", game.getId());
    }

    // 添加清理缓存的方法
    private void cleanupGameCache(Integer gameId) {
        try {
            // 清理活动基本信息
            redisUtil.del(RedisKeys.INFO + gameId);

            // 清理活动奖品信息
            redisUtil.del(RedisKeys.INFO + "products" + gameId);

            // 清理活动规则信息
            redisUtil.del(RedisKeys.MAXGOAL + gameId);
            redisUtil.del(RedisKeys.MAXENTER + gameId);

            // 清理令牌相关数据
            redisUtil.del(RedisKeys.TOKENS + gameId);

            log.debug("活动 {} 旧缓存数据已清理", gameId);
        } catch (Exception e) {
            log.warn("清理活动 {} 缓存数据时出错: {}", gameId, e.getMessage());
        }
    }

}
