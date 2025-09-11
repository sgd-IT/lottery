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
 * 查找未来180分钟内（含），要开始的活动
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
        // 获取当前时间的Calendar实例
        Calendar calendar = Calendar.getInstance();
        // 清除毫秒部分
        calendar.set(Calendar.MILLISECOND, 0);
        // 获取不带毫秒的Date对象
        Date now = calendar.getTime();
        log.info("开始执行定时任务，当前时间: {}", now);

        // 分布式锁，防止重复启动任务
        if (!redisUtil.setNx("game_task_" + now.getTime(), 1, 60L)) {
            log.info("任务已被其他服务器执行，当前服务器跳过");
            return;
        }

        // 1. 查找未来180分钟内（含），要开始的活动
        QueryWrapper<CardGame> wrapper = new QueryWrapper<>();
        wrapper.gt("starttime", now);
        wrapper.le("starttime", DateUtils.addMinutes(now, 180));
        List<CardGame> games = gameService.list(wrapper);
        log.info("查询到 {} 个活动需要预热", games.size());

        // 2. 遍历活动，预热缓存
        // 把（开始时间 > 当前时间）&& （开始时间 <= 当前时间+180分钟）的活动及相关信息放入redis，完成预热
        for (CardGame game : games) {
            log.info("开始预热活动: ID={}, 标题={}", game.getId(), game.getTitle());

            // 清理旧缓存
            cleanupGameCache(game.getId());

            // 活动基本信息
            redisUtil.set(RedisKeys.INFO + game.getId(), game, -1);
            log.debug("活动 {} 基本信息已缓存", game.getId());

            // 活动奖品信息
            List<CardProductDto> products = gameLoadService.getByGameId(game.getId());
            redisUtil.set(RedisKeys.INFO + "products" + game.getId(), products, -1);
            log.debug("活动 {} 奖品信息已缓存，共 {} 个奖品", game.getId(), products.size());

            // 活动策略
            QueryWrapper<CardGameRules> rulesWrapper = new QueryWrapper<>();
            rulesWrapper.eq("gameid", game.getId());
            List<CardGameRules> rules = gameRulesService.list(rulesWrapper);
            log.debug("活动 {} 规则数量: {}", game.getId(), rules.size());
            for (CardGameRules rule : rules) {
                if (rule.getUserlevel() != null) {
                    redisUtil.hset(RedisKeys.MAXGOAL + game.getId(), rule.getUserlevel() + "", rule.getGoalTimes());
                    redisUtil.hset(RedisKeys.MAXENTER + game.getId(), rule.getUserlevel() + "", rule.getEnterTimes());
                    redisUtil.hset(RedisKeys.RANDOMRATE + game.getId(), rule.getUserlevel() + "", rule.getRandomRate());
                    log.debug("活动 {} 级别 {} 规则已缓存", game.getId(), rule.getUserlevel());
                } else {
                    log.warn("活动 {} 存在用户等级为null的规则", game.getId());
                }
            }

            // 获取游戏产品关联信息
            List<CardGameProduct> gameProducts = gameProductService.list(
                    new QueryWrapper<CardGameProduct>().eq("gameid", game.getId())
            );

            // 抽奖令牌桶
            log.info("开始为活动 {} 生成令牌桶", game.getId());
            tokenBucket(game, products, gameProducts);
            log.info("活动 {} 预热完成", game.getId());

            // 活动状态变更为已预热，禁止管理后台再随便变动
            game.setStatus(1);
            gameService.updateById(game);
        }
        log.info("定时任务执行完成");
    }

    private void tokenBucket(CardGame game, List<CardProductDto> products, List<CardGameProduct> gameProducts) {
        log.debug("开始为活动 {} 生成令牌桶，奖品数量: {}", game.getId(), products.size());
        long startTime = game.getStarttime().getTime() / 1000;
        long endTime = game.getEndtime().getTime() / 1000;
        long runTime = endTime - startTime;

        // 计算过期时间（活动结束时间+一定缓冲时间）
        long expireTime = (game.getEndtime().getTime() - System.currentTimeMillis()) / 1000 + 3600; // 多加1小时缓冲

        List<Long> tokenList = new ArrayList<>();

        // 统计总奖品数量
        int totalProducts = 0;
        for (CardGameProduct product : gameProducts) {
            totalProducts += product.getAmount();
        }
        log.debug("活动 {} 总奖品数量: {}", game.getId(), totalProducts);

        // 生成令牌
        Random random = new Random();
        Set<Long> tokenSet = new HashSet<>();

        // 生成唯一的时间戳令牌用于标识奖品
        // 通过添加随机因子和重复检查确保每个令牌的唯一性
        for (int i = 0; i < totalProducts; i++) {
            long rndOffset = (long) (random.nextDouble() * runTime);
            long rnd = startTime + rndOffset;
            // 乘1000并加随机数防止时间段奖品多时重复
            long token = rnd * 1000 + random.nextInt(999);

            // 检查并重新生成令牌直到获得唯一值
            while (tokenSet.contains(token) || token < 0) {
                rndOffset = (long) (random.nextDouble() * runTime);
                rnd = startTime + rndOffset;
                token = rnd * 1000 + random.nextInt(999);
            }
            tokenSet.add(token);
            tokenList.add(token);
        }

        // 排序令牌
        Collections.sort(tokenList);
        log.debug("活动 {} 令牌生成完成，令牌数量: {}", game.getId(), tokenList.size());

        // 奖品映射信息
        createPrizeMapping(game, products, gameProducts, tokenList, expireTime);

        // 把令牌列表写入redis，设置过期时间
        if (!tokenList.isEmpty()) {
            redisUtil.rightPushAll(RedisKeys.TOKENS + game.getId(), tokenList);
            redisUtil.expire(RedisKeys.TOKENS + game.getId(), expireTime);
            log.info("活动 {} 令牌桶生成完成，令牌数量: {}", game.getId(), tokenList.size());
        }
    }

    /**
     * 奖品映射信息
     *
     * @param game
     * @param products
     * @param gameProducts
     * @param tokenList
     * @param expireTime
     */
    private void createPrizeMapping(CardGame game, List<CardProductDto> products,
                                    List<CardGameProduct> gameProducts, List<Long> tokenList,
                                    long expireTime) {
        log.debug("开始为活动 {} 创建奖品映射", game.getId());

        // 创建productid到CardProductDto的映射
        Map<Integer, CardProductDto> productMap = new HashMap<>();
        for (CardProductDto product : products) {
            productMap.put(product.getId(), product);
        }

        // 为每个CardGameProduct生成对应的令牌
        int tokenIndex = 0;
        for (CardGameProduct gameProduct : gameProducts) {
            Integer productId = gameProduct.getProductid();
            CardProductDto product = productMap.get(productId);
            if (product != null) {
                for (int i = 0; i < gameProduct.getAmount() && tokenIndex < tokenList.size(); i++) {
                    Long token = tokenList.get(tokenIndex++);
                    // 以活动id_令牌为key，奖品信息(CardProductDto)为value
                    redisUtil.set(RedisKeys.TOKEN + game.getId() + "_" + token, product, expireTime);
                }
            } else {
                log.warn("活动 {} 未找到产品ID为 {} 的奖品信息", game.getId(), productId);
            }
        }
        log.debug("活动 {} 奖品映射创建完成", game.getId());
    }

    /**
     * 清理活动相关缓存
     */
    private void cleanupGameCache(Integer gameId) {
        try {
            // 只清理当前活动的相关缓存，避免影响其他活动
            String gameKey = String.valueOf(gameId);

            // 清理活动基本信息
            redisUtil.del(RedisKeys.INFO + gameKey);

            // 清理活动奖品信息
            redisUtil.del(RedisKeys.INFO + "products" + gameKey);

            // 清理活动规则信息
            redisUtil.del(RedisKeys.MAXGOAL + gameKey);
            redisUtil.del(RedisKeys.MAXENTER + gameKey);
            redisUtil.del(RedisKeys.RANDOMRATE + gameKey);

            // 清理令牌队列
            redisUtil.del(RedisKeys.TOKENS + gameKey);

            // 清理当前活动的所有令牌相关key
            Set<String> tokenKeys = redisUtil.keys(RedisKeys.TOKEN + gameKey + "_*");
            if (tokenKeys != null && !tokenKeys.isEmpty()) {
                redisUtil.del(tokenKeys.toArray(new String[0]));
            }

            log.debug("活动 {} 旧缓存数据已清理完成", gameId);
        } catch (Exception e) {
            log.warn("清理活动 {} 缓存数据时出错: {}", gameId, e.getMessage());
        }
    }
}
