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

package io.seqera.wave.service.data.queue.impl

import java.time.Duration

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.context.annotation.Requires
import io.seqera.wave.redis.RedisService
import io.seqera.wave.service.data.queue.MessageQueue
import jakarta.inject.Inject
import jakarta.inject.Singleton
/**
 * Implements a message broker using Redis list
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@Requires(env = 'redis')
@Singleton
@CompileStatic
class RedisMessageQueue implements MessageQueue<String>  {

    @Inject
    RedisService redisService

    @Override
    void offer(String target, String message) {
        redisService.lpush(target, message)
    }

    @Override
    String poll(String target) {
        return redisService.rpop(target)
    }

    @Override
    String poll(String target, Duration duration) {
        double d = duration.toMillis() / 1000.0
        final entry = redisService.brpop(d, target)
        return entry ?: null
    }
}
