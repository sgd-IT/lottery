
-- 获取token并判定是否中奖
-- 返回值：-1=可用抽奖次数不足，-2=奖品被抽光，0=有令牌但你未中奖，else=中奖，返回了拿到的令牌
redis.log(redis.LOG_NOTICE, "-- 开始抽奖操作 ：gameId,userId,maxGoal=" .. KEYS[1] .. ',' .. KEYS[2] .. ',' .. KEYS[3])

-- 用户已中奖次数
local usergoal = redis.call('get','user_hit_' .. KEYS[1] .. '_' .. KEYS[2])

-- 中奖次数大于最大允许次数，返回-1
if usergoal ~= false and tonumber(KEYS[3]) ~=0 and tonumber(usergoal) >= tonumber(KEYS[3]) then
    redis.log(redis.LOG_NOTICE, "-- 中奖次数超出上限，tonumber(usergoal) > tonumber(KEYS[3]) , return -1")
    return -1
end

-- 从左侧获取一个token
local token = redis.call('lpop', 'game_tokens_' .. KEYS[1])

-- 当前系统时间
local curtime = redis.call('TIME')[1]
redis.log(redis.LOG_NOTICE, "-- 当前时间，curtime  = " .. curtime)


if token ~= false then
    redis.log(redis.LOG_NOTICE, "-- 获取到令牌，token = " .. token)
    -- token是毫秒，并且尾部加了3位随机数，curtime是秒，相差6位
    if tonumber(token)/1000 > curtime*1000 then
        redis.log(redis.LOG_NOTICE, "-- 令牌无效，tonumber(token)/1000 > curtime*1000 , return 0")
        redis.call('lpush', 'game_tokens_' .. KEYS[1], token)
        return 0
    -- 否则表示token有效，中奖，用户中奖数+1，返回token令牌
    else
        local hit = redis.call('incr','user_hit_' .. KEYS[1] .. '_' .. KEYS[2])
        redis.log(redis.LOG_NOTICE, "-- 令牌有效，中奖！ return token,userGoal=" .. tonumber(hit))
        return tonumber(token)
    end
else
    -- 取不到token，表示抽光了，返回-2
    redis.log(redis.LOG_NOTICE, "-- 取不到token，奖品已抽光，返回-2")
    return -2
end