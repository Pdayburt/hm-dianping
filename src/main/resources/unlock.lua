---
--- Generated by Luanalysis
--- Created by xuchao.
--- DateTime: 2023/3/31 16:39
---
if (redis.call('get',KEYS[1]) == ARGV[1]) then
    return redis.call('del',KEYS[1])
end
return 0