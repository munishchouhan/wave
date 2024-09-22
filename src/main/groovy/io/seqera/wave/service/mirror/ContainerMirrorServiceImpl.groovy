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

package io.seqera.wave.service.mirror

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.scheduling.TaskExecutors
import io.seqera.wave.service.builder.BuildTrack
import io.seqera.wave.service.job.JobHandler
import io.seqera.wave.service.job.JobService
import io.seqera.wave.service.job.JobSpec
import io.seqera.wave.service.job.JobState
import io.seqera.wave.service.persistence.PersistenceService
import jakarta.inject.Inject
import jakarta.inject.Named
import jakarta.inject.Singleton

/**
 * Implement a service to mirror a container image to a repository specified by the user
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@Singleton
@Named('Mirror')
@CompileStatic
class ContainerMirrorServiceImpl implements ContainerMirrorService, JobHandler<MirrorState> {

    @Inject
    private MirrorStateStore store

    @Inject
    private JobService jobService

    @Inject
    @Named(TaskExecutors.IO)
    private ExecutorService ioExecutor

    @Inject
    private PersistenceService persistence

    /**
     * {@inheritDoc}
     */
    @Override
    BuildTrack mirrorImage(MirrorRequest request) {
        if( store.putIfAbsent(request.targetImage, MirrorState.from(request))) {
            log.info "== Container mirror submitted - request=$request"
            jobService.launchMirror(request)
            return new BuildTrack(request.id, request.targetImage, false)
        }
        final ret = store.get(request.targetImage)
        if( ret ) {
            log.info "== Container mirror hit cache - request=$request"
            // note: mark as cached only if the build result is 'done'
            // if the build is still in progress it should be marked as not cached
            // so that the client will wait for the container completion
            return new BuildTrack(ret.mirrorId, ret.targetImage, ret.done())
        }
        // invalid state
        throw new IllegalStateException("Unable to determine mirror status for '$request.targetImage'")
    }

    /**
     * {@inheritDoc}
     */
    @Override
    CompletableFuture<MirrorState> awaitCompletion(String targetImage) {
        return CompletableFuture.supplyAsync(()-> store.awaitCompletion(targetImage), ioExecutor)
    }

    /**
     * {@inheritDoc}
     */
    @Override
    MirrorState getMirrorState(String mirrorId) {
        store.getByRecordId(mirrorId) ?: persistence.loadMirrorState(mirrorId)
    }

    /**
     * {@inheritDoc}
     */
    @Override
    MirrorState getJobRecord(JobSpec jobSpec) {
        store.get(jobSpec.recordId)
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void onJobCompletion(JobSpec jobSpec, MirrorState mirror, JobState jobState) {
        final result = mirror.complete(jobState.exitCode, jobState.stdout)
        store.put(mirror.targetImage, result)
        persistence.saveMirrorState(result)
        log.debug "Mirror container completed - job=${jobSpec.operationName}; result=${result}; state=${jobState}"
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void onJobTimeout(JobSpec jobSpec, MirrorState mirror) {
        final result = mirror.complete(null, "Container mirror timed out")
        store.put(mirror.targetImage, result)
        persistence.saveMirrorState(result)
        log.warn "Mirror container timed out - job=${jobSpec.operationName}; result=${result}"
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void onJobException(JobSpec jobSpec, MirrorState mirror, Throwable error) {
        final result = mirror.complete(null, error.message)
        store.put(mirror.targetImage, result)
        persistence.saveMirrorState(result)
        log.error("Mirror container errored - job=${jobSpec.operationName}; result=${result}", error)
    }
}
