/*
 *  Wave, containers provisioning service
 *  Copyright (c) 2023-2024, Seqera Labs
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.seqera.wave.store.state.impl

import java.time.Duration

import groovy.transform.CompileStatic
import io.micronaut.context.annotation.Requires
import jakarta.inject.Inject
import jakarta.inject.Singleton
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.params.SetParams
/**
 * Redis based implementation for a {@link StateProvider}
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Requires(property = 'redis.uri')
@Singleton
@CompileStatic
class RedisStateProvider implements StateProvider<String,String> {

    @Inject
    private JedisPool pool

    @Override
    String get(String key) {
        try( Jedis conn=pool.getResource() ) {
            return conn.get(key)
        }
    }

    @Override
    void put(String key, String value) {
        put(key, value, null)
    }

    @Override
    void put(String key, String value, Duration ttl) {
        try( Jedis conn=pool.getResource() ) {
            final params = new SetParams()
            if( ttl )
                params.px(ttl.toMillis())
            conn.set(key, value, params)
        }
    }

    @Override
    boolean putIfAbsent(String key, String value) {
        putIfAbsent(key, value, null)
    }

    @Override
    boolean putIfAbsent(String key, String value, Duration ttl) {
        try( Jedis conn=pool.getResource() ) {
            final params = new SetParams().nx()
            if( ttl )
                params.px(ttl.toMillis())
            final result = conn.set(key, value, params)
            return result == 'OK'
        }
    }

    /*
     * Set a value only the specified key does not exists, if the value can be set
     * the counter identified by the key provided via 'KEYS[2]' is incremented by 1,
     *
     * If the key already exists return the current key value.
     */
    static final private String PUT_AND_INC = """
        if redis.call('EXISTS', KEYS[1]) == 0 then
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            local counter_value = redis.call('INCR', KEYS[2])
            return {1, ARGV[1], counter_value} 
        else
            return {0, redis.call('GET', KEYS[1]), redis.call('GET', KEYS[2])}
        end
        """

    // same as above, but using hincr for the counter
    static final private String PUT_AND_HINCRBY = """
        if redis.call('EXISTS', KEYS[1]) == 0 then
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            local counter_value = redis.call('HINCRBY', KEYS[2], KEYS[3], 1)
            return {1, ARGV[1], counter_value} 
        else
            return {0, redis.call('GET', KEYS[1]), redis.call('HGET', KEYS[2], KEYS[3])}
        end
        """

    protected eval0(Jedis jedis, String key, String value, Duration ttl, String counterKey) {
        final buildPrefix = 'build-counters/v1/'
        if( counterKey.startsWith(buildPrefix) ) {
            final field = counterKey.substring(buildPrefix.size())
            final kount = counterKey.substring(0,buildPrefix.size()-1)
            return jedis.eval(PUT_AND_HINCRBY, 3, key, kount, field, value, ttl.toMillis().toString())
        }
        else {
            return jedis.eval(PUT_AND_INC, 2, key, counterKey, value, ttl.toMillis().toString())
        }
    }

    @Override
    Tuple3<Boolean,String,Integer> putIfAbsent(String key, String value, Duration ttl, String counterKey) {
        try( Jedis jedis=pool.getResource() )  {
            final result = eval0(jedis, key, value, ttl, counterKey)
            return new Tuple3<>((result as List)[0] == 1,
                    (result as List)[1] as String,
                    (result as List)[2] as Integer)
        }
    }

    @Override
    void remove(String key) {
        try( Jedis conn=pool.getResource() ) {
            conn.del(key)
        }
    }

    @Override
    void clear() {
        try( Jedis conn=pool.getResource() ) {
            conn.flushAll()
        }
    }

}
