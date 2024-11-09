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

package io.seqera.wave.service.logs

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.context.annotation.Requires
import io.micronaut.http.server.types.files.StreamedFile
import io.micronaut.objectstorage.ObjectStorageEntry
import io.micronaut.objectstorage.ObjectStorageOperations
import io.micronaut.objectstorage.request.UploadRequest
import io.micronaut.runtime.event.annotation.EventListener
import io.micronaut.scheduling.TaskExecutors
import io.seqera.wave.configuration.LogsConfig
import io.seqera.wave.service.builder.BuildEvent
import io.seqera.wave.service.builder.BuildRequest
import io.seqera.wave.service.persistence.PersistenceService
import jakarta.annotation.PostConstruct
import jakarta.inject.Inject
import jakarta.inject.Named
import jakarta.inject.Singleton
import org.apache.commons.io.input.BoundedInputStream
import static org.apache.commons.lang3.StringUtils.strip
/**
 * Implements Service  to manage logs from an Object store
 *
 * @author Munish Chouhan <munish.chouhan@seqera.io>
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@Singleton
@CompileStatic
@Requires(bean = LogsConfig)
class BuildLogServiceImpl implements BuildLogService {

    private static final String CONDA_LOCK_START = ">> CONDA_LOCK_START"

    private static final String CONDA_LOCK_END = "<< CONDA_LOCK_END"

    @Inject
    @Named('build-logs')
    private ObjectStorageOperations<?, ?, ?> objectStorageOperations

    @Inject
    private PersistenceService persistenceService

    @Inject
    private LogsConfig config

    @Inject
    @Named(TaskExecutors.IO)
    private volatile ExecutorService ioExecutor

    @PostConstruct
    private void init() {
        log.info "Creating Build log service - config=${config}"
    }

    protected String logKey(String buildId) {
        if( !buildId )
            return null
        if( !config.prefix )
            return buildId + '.log'
        final base = strip(config.prefix, '/')
        return "${base}/${buildId}.log"
    }

    @EventListener
    void onBuildEvent(BuildEvent event) {
        if(event.result.logs) {
            CompletableFuture.supplyAsync(() -> storeLog(event.result.buildId, event.result.logs), ioExecutor)
        }
    }

    @Override
    void storeLog(String buildId, String content) {

        try {
            final String logs = removeCondaLockFile(content)
            log.debug "Storing logs for buildId: $buildId"
            final uploadRequest = UploadRequest.fromBytes(logs.bytes, logKey(buildId))
            objectStorageOperations.upload(uploadRequest)
            // check if needed to store the conda lock
            final condaLock = content.contains(CONDA_LOCK_START)
            if ( condaLock )
                storeCondaLock(buildId, content)
        }
        catch (Exception e) {
            log.warn "Unable to store logs for buildId: $buildId  - reason: ${e.message}", e
        }
    }

    @Override
    StreamedFile fetchLogStream(String buildId) {
        fetchLogStream0(buildId) ?: fetchLogStream0(BuildRequest.legacyBuildId(buildId))
    }

    private StreamedFile fetchLogStream0(String buildId) {
        if( !buildId ) return null
        final Optional<ObjectStorageEntry<?>> result = objectStorageOperations.retrieve(logKey(buildId))
        return result.isPresent() ? result.get().toStreamedFile() : null
    }

    @Override
    BuildLog fetchLogString(String buildId) {
        final result = fetchLogStream(buildId)
        if( !result )
            return null
        final logs = new BoundedInputStream(result.getInputStream(), config.maxLength).getText()
        return new BuildLog(logs, logs.length()>=config.maxLength)
    }

    protected static removeCondaLockFile(String logs) {
        if(logs.indexOf(CONDA_LOCK_START) < 0 ) {
            return logs
        }
        return logs.replaceAll(/(?s)\n?#\d+ \d+\.\d+ $CONDA_LOCK_START.*?$CONDA_LOCK_END\n?/, '\n')
    }

    protected void storeCondaLock(String buildId, String logs) {
        if( !logs ) return
        try {
            String condaLock = extractCondaLockFile(logs)
            if (condaLock){
                log.debug "Storing conda lock for buildId: $buildId"
                final uploadRequest = UploadRequest.fromBytes(condaLock.bytes, condaLockKey(buildId))
                objectStorageOperations.upload(uploadRequest)
            }
        }
        catch (Exception e) {
            log.warn "Unable to store condalock for buildId: $buildId  - reason: ${e.message}", e
        }
    }

    protected String condaLockKey(String buildId) {
        if( !buildId )
            return null
        if( !config.condaLockPrefix )
            return buildId + '.lock'
        final base = strip(config.condaLockPrefix, '/')
        return "${base}/${buildId}.lock"
    }

    @Override
    String fetchCondaLockString(String buildId) {
        final result = fetchCondaLockStream(buildId)
        if( !result )
            return null
        return result.getInputStream().getText()
    }

    @Override
    StreamedFile fetchCondaLockStream(String buildId) {
        if( !buildId ) return null
        final Optional<ObjectStorageEntry<?>> result = objectStorageOperations.retrieve(condaLockKey(buildId))
        return result.isPresent() ? result.get().toStreamedFile() : null
    }

    protected static String extractCondaLockFile(String logs) {
            int start = logs.lastIndexOf(CONDA_LOCK_START)
            int end = logs.lastIndexOf(CONDA_LOCK_END)
            if( start >= end ) { // when build fails, there will be commands in the logs, so to avoid extracting wrong content
                return null
            }
            return logs.substring(start + CONDA_LOCK_START.length(), end)
                    .replaceAll(/#\d+ \d+\.\d+\s*/, '')
    }

}
