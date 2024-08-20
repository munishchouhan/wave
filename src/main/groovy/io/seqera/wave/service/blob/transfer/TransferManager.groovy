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

package io.seqera.wave.service.blob.transfer

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.context.annotation.Context
import io.micronaut.context.annotation.Requires
import io.micronaut.scheduling.TaskExecutors
import io.seqera.wave.configuration.BlobCacheConfig
import io.seqera.wave.service.blob.BlobCacheInfo
import io.seqera.wave.service.blob.impl.BlobCacheStore
import io.seqera.wave.util.ExponentialAttempt
import jakarta.annotation.PostConstruct
import jakarta.inject.Inject
import jakarta.inject.Named
/**
 * Implement the logic to handle Blob cache transfer (uploads)
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@Context
@CompileStatic
@Requires(property = 'wave.blobCache.enabled', value = 'true')
class TransferManager  {

    @Inject
    private TransferStrategy transferStrategy

    @Inject
    private BlobCacheStore blobStore

    @Inject
    private BlobCacheConfig blobConfig

    @Inject
    private TransferQueue queue

    @Inject
    @Named(TaskExecutors.IO)
    private ExecutorService executor

    private final ExponentialAttempt attempt = new ExponentialAttempt()

    @PostConstruct
    private init() {
        CompletableFuture.supplyAsync(()->run(), executor)
    }

    void run() {
        log.info "+ Starting Blob cache transfer manager"
        while( !Thread.currentThread().isInterrupted() ) {
            try {
                final transferId = queue.poll(blobConfig.statusDelay)

                if( transferId ) {
                    handle(transferId)
                    attempt.reset()
                }
            }
            catch (InterruptedException e) {
                log.debug "Interrupting transfer manager watcher thread"
                break
            }
            catch (Throwable e) {
                final d0 = attempt.delay()
                log.error("Transfer manager unexpected error (await: ${d0}) - cause: ${e.message}", e)
                sleep(d0.toMillis())
            }
        }
    }

    /**
     * Handles the blob transfer operation i.e. check and update the current upload status
     *
     * @param blobId the blob cache id i.e. {@link BlobCacheInfo#id()}
     */
    protected void handle(String blobId) {
        try {
            final blob = blobStore.get(blobId)
            if( !blob ) {
                log.error "Unknown blob transfer with id: $blobId"
                return
            }
            try {
                handle0(blob)
            }
            catch (Throwable t) {
                log.error("Unexpected error caching blob '${blob.objectUri}' - job name '${blob.jobName}", t)
                blobStore.put(blobId, blob.failed("Unexpected error caching blob '${blob.locationUri}' - job name '${blob.jobName}'"))
            }
        }
        catch (InterruptedException e) {
            // re-queue the transfer to not lose it
            queue.offer(blobId)
            // re-throw the exception
            throw e
        }
    }

    protected void handle0(BlobCacheInfo info) {
        final duration = Duration.between(info.creationTime, Instant.now())
        final transfer = transferStrategy.status(info)
        log.trace "Blob cache transfer name=${info.jobName}; state=${transfer}; object=${info.objectUri}"
        final done =
                transfer.completed() ||
                // considered failed when remain in unknown status too long         
                (transfer.status==Transfer.Status.UNKNOWN && duration>blobConfig.graceDuration)
        if( done ) {
            // use a short time-to-live for failed downloads
            // this is needed to allow re-try caching of failure transfers
            final ttl = transfer.succeeded()
                    ? blobConfig.statusDuration
                    : blobConfig.failureDuration
            // update the blob status
            final result = transfer.succeeded()
                    ? info.completed(transfer.exitCode, transfer.stdout)
                    : info.failed(transfer.stdout)
            blobStore.storeBlob(info.id(), result, ttl)
            log.debug "== Blob cache completed for object '${info.objectUri}'; id=${info.objectUri}; status=${result.exitStatus}; duration=${result.duration()}"
            // finally cleanup the job
            transferStrategy.cleanup(result)
            return
        }
        // set the await timeout nearly double as the blob transfer timeout, this because the
        // transfer pod can spend `timeout` time in pending status awaiting to be scheduled
        // and the same `timeout` time amount carrying out the transfer (upload) operation
        final max = (blobConfig.transferTimeout.toMillis() * 2.10) as long
        if( duration.toMillis()>max ) {
            final result = info.failed("Blob cache transfer timed out - id: ${info.objectUri}; object: ${info.objectUri}")
            log.warn "== Blob cache completed for object '${info.objectUri}'; id=${info.objectUri}; duration=${result.duration()}"
            blobStore.storeBlob(info.id(), result, blobConfig.failureDuration)
        }
        else {
            log.trace "== Blob cache pending for completion $info"
            // re-schedule for a new check
            queue.offer(info.id())
        }
    }

}
