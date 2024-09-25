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

package io.seqera.wave.service.scan

import java.time.Duration
import java.time.Instant

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import groovy.transform.ToString
import io.seqera.wave.service.job.JobEntry
import io.seqera.wave.store.state.StateEntry

/**
 * Model for scan result
 *
 * @author Munish Chouhan <munish.chouhan@seqera.io>
 */
@ToString(includePackage = false, includeNames = true)
@Canonical
@CompileStatic
class ScanState implements StateEntry<String>, JobEntry {

    static final public String PENDING = 'PENDING'
    static final public String SUCCEEDED = 'SUCCEEDED'
    static final public String FAILED = 'FAILED'

    final String id
    final String buildId
    final String containerImage
    final Instant startTime
    final Duration duration
    final String status
    final List<ScanVulnerability> vulnerabilities
    final Integer exitCode
    final String logs

    @Override
    String getKey() {
        return id
    }

    @Override
    boolean done() {
        return duration != null
    }

    boolean isSucceeded() { status==SUCCEEDED }

    @Deprecated
    boolean isCompleted() { done() }

    ScanState success(List<ScanVulnerability> vulnerabilities){
        return new ScanState(
                this.id,
                this.buildId,
                this.containerImage,
                this.startTime,
                Duration.between(this.startTime, Instant.now()),
                SUCCEEDED,
                vulnerabilities,
                0 )
    }

    ScanState failure(Integer exitCode, String logs){
        return new ScanState(this.id, this.buildId, this.containerImage, this.startTime, Duration.between(this.startTime, Instant.now()), FAILED, List.of(), exitCode, logs)
    }

    static ScanState failure(ScanRequest request){
        return new ScanState(request.id, request.buildId, request.targetImage, request.creationTime, Duration.between(request.creationTime, Instant.now()), FAILED, List.of())
    }

    static ScanState pending(String scanId, String buildId, String containerImage) {
        return new ScanState(scanId, buildId, containerImage, Instant.now(), null, PENDING, List.of())
    }

    static ScanState create(String scanId, String buildId, String containerImage, Instant startTime, Duration duration1, String status, List<ScanVulnerability> vulnerabilities){
        return new ScanState(scanId, buildId, containerImage, startTime, duration1, status, vulnerabilities)
    }
}
