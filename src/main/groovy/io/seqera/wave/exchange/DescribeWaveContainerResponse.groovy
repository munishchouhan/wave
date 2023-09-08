/*
 *  Copyright (c) 2023, Seqera Labs.
 *
 *  This Source Code Form is subject to the terms of the Mozilla Public
 *  License, v. 2.0. If a copy of the MPL was not distributed with this
 *  file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 *  This Source Code Form is "Incompatible With Secondary Licenses", as
 *  defined by the Mozilla Public License, v. 2.0.
 */

package io.seqera.wave.exchange

import java.time.Instant

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import io.seqera.wave.api.ContainerConfig
import io.seqera.wave.service.persistence.WaveContainerRecord
import io.seqera.wave.tower.User

/**
 * Model a container request record
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Canonical
@CompileStatic
class DescribeWaveContainerResponse {

    @Canonical
    static class RequestInfo {
        final User user
        final Long workspaceId
        final String containerImage
        final ContainerConfig containerConfig
        final String platform
        final String towerEndpoint
        final String fingerprint
        final Instant timestamp
        final String zoneId
        final String ipAddress

        RequestInfo() {}

        RequestInfo(WaveContainerRecord data) {
            this.user = data.user
            this.workspaceId = data.workspaceId
            this.containerImage = data.containerImage
            this.containerConfig = data.containerConfig
            this.platform = data.platform
            this.towerEndpoint = data.towerEndpoint
            this.fingerprint = data.fingerprint
            this.timestamp = data.timestamp
            this.zoneId = data.zoneId
            this.ipAddress = data.ipAddress
        }
    }

    @Canonical
    static class BuildInfo {
        final String containerFile
        final String condaFile
        final String buildRepository
        final String cacheRepository
        final String buildId
        final Boolean buildNew
        final Boolean freeze

        BuildInfo() {}

        BuildInfo(WaveContainerRecord data) {
            this.containerFile = data.containerFile
            this.condaFile = data.condaFile
            this.buildRepository = data.buildRepository
            this.cacheRepository = data.cacheRepository
            this.buildId = data.buildId
            this.buildNew = data.buildNew
            this.freeze = data.freeze
        }
    }

    @Canonical
    static class ContainerInfo {
        String image
        String digest
    }

    final String token

    final Instant expiration

    final RequestInfo request

    final BuildInfo build

    final ContainerInfo source

    final ContainerInfo wave

    static DescribeWaveContainerResponse create(String token, WaveContainerRecord data) {
        final request = new RequestInfo(data)
        final build = new BuildInfo(data)
        final source = new ContainerInfo(data.sourceImage, data.sourceDigest)
        final wave = new ContainerInfo(data.waveImage, data.waveDigest)
        return new DescribeWaveContainerResponse(token, data.expiration, request, build, source, wave)
    }
}
