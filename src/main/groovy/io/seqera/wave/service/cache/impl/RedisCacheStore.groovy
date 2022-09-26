package io.seqera.wave.service.cache.impl

import java.time.Duration

import groovy.transform.CompileStatic
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.seqera.wave.service.cache.CacheStore
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
class RedisCacheStore implements CacheStore<String,String> {

    private StatefulRedisConnection<String,String> redisConn

    private Duration duration

    @Override
    String get(String key) {
        return redisConn.sync().get(key)
    }

    @Override
    void put(String key, String value) {
        redisConn.sync().psetex(key, duration.toMillis(), value)
    }

    @Override
    boolean putIfAbsent(String key, String value) {
        final SetArgs args = SetArgs.Builder.ex(duration).nx()
        final result = redisConn.sync().set(key, value, args)
        return result == 'OK'
    }

    @Override
    void remove(String key) {
        redisConn.sync().del(key)
    }

}
