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

package io.seqera.wave.service.job

import java.time.Duration

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.wave.service.data.stream.AbstractMessageStream
import io.seqera.wave.service.data.stream.MessageConsumer
import io.seqera.wave.service.data.stream.MessageStream
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
/**
 * Implements a simple persistent FIFO queue
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@Singleton
@CompileStatic
class JobQueue extends AbstractMessageStream<JobSpec> {

    final private static String STREAM_NAME = 'jobs-queue/v1:'

    private volatile JobConfig config

    JobQueue(MessageStream<String> target, JobConfig config) {
        super(target)
        this.config = config
        log.debug "Created job queue"
    }

    @Override
    protected String name() {
        return 'jobs-queue'
    }

    @Override
    protected Duration pollInterval() {
        return config.pollInterval
    }

    final void offer(JobSpec jobSpec) {
        super.offer(STREAM_NAME, jobSpec)
    }

    final void addConsumer(MessageConsumer<JobSpec> consumer) {
        super.addConsumer(STREAM_NAME, consumer)
    }

    @PreDestroy
    void destroy() {
        log.debug "Shutting down job queue"
        this.close()
    }
}
