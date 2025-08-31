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

package io.seqera.wave.service.builder

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import io.seqera.wave.core.ContainerPlatform

/**
 * Model a multi-platform container build request
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@EqualsAndHashCode
@CompileStatic
class MultiPlatformBuildRequest {

    /**
     * The base build request containing common properties
     */
    final BuildRequest baseRequest

    /**
     * List of target platforms for this multi-platform build
     */
    final List<ContainerPlatform> platforms

    /**
     * Whether this is a single-platform build (for backward compatibility)
     */
    final boolean singlePlatform

    MultiPlatformBuildRequest(BuildRequest baseRequest, List<ContainerPlatform> platforms) {
        this.baseRequest = baseRequest
        this.platforms = platforms ?: [ContainerPlatform.DEFAULT]
        this.singlePlatform = this.platforms.size() == 1
    }

    /**
     * Create individual BuildRequest objects for each platform
     */
    List<BuildRequest> createPlatformRequests() {
        if (singlePlatform) {
            return [baseRequest]
        }

        return platforms.collect { platform ->
            new BuildRequest(
                baseRequest.containerId,
                baseRequest.containerFile,
                baseRequest.condaFile,
                baseRequest.workspace,
                baseRequest.targetImage,
                baseRequest.identity,
                platform,  // Use specific platform
                baseRequest.cacheRepository,
                baseRequest.ip,
                baseRequest.configJson,
                baseRequest.offsetId,
                baseRequest.containerConfig,
                baseRequest.scanId,
                baseRequest.buildContext,
                baseRequest.format,
                baseRequest.maxDuration,
                baseRequest.compression
            )
        }
    }

    /**
     * Get the platform string representation for buildkit
     */
    String getPlatformString() {
        return platforms.collect { it.toString() }.join(',')
    }

    boolean isMultiPlatform() {
        return !singlePlatform
    }

    @Override
    String toString() {
        return "MultiPlatformBuildRequest[baseRequest=$baseRequest; platforms=$platforms; singlePlatform=$singlePlatform]"
    }
}