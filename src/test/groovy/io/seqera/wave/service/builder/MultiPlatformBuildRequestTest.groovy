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

import spock.lang.Specification

import java.nio.file.Path
import java.time.Duration

import io.seqera.wave.api.BuildCompression
import io.seqera.wave.api.ContainerConfig
import io.seqera.wave.core.ContainerPlatform
import io.seqera.wave.tower.PlatformId

/**
 * Test for MultiPlatformBuildRequest
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class MultiPlatformBuildRequestTest extends Specification {

    def 'should create single platform build request' () {
        given:
        def req = new BuildRequest(
                containerId: 'c168dba125e28777',
                containerFile: 'FROM ubuntu:latest',
                workspace: Path.of('/work/foo'),
                platform: ContainerPlatform.of('linux/amd64'),
                targetImage: 'quay.io/wave:c168dba125e28777',
                cacheRepository: 'reg.io/wave/build/cache',
        )
        def platforms = [ContainerPlatform.of('linux/amd64')]

        when:
        def multiReq = new MultiPlatformBuildRequest(req, platforms)

        then:
        multiReq.singlePlatform
        !multiReq.multiPlatform
        multiReq.platforms.size() == 1
        multiReq.platformString == 'linux/amd64'
        multiReq.createPlatformRequests().size() == 1
    }

    def 'should create multi platform build request' () {
        given:
        def req = new BuildRequest(
                containerId: 'c168dba125e28777',
                containerFile: 'FROM ubuntu:latest',
                workspace: Path.of('/work/foo'),
                platform: ContainerPlatform.of('linux/amd64'),
                targetImage: 'quay.io/wave:c168dba125e28777',
                cacheRepository: 'reg.io/wave/build/cache',
        )
        def platforms = [
                ContainerPlatform.of('linux/amd64'),
                ContainerPlatform.of('linux/arm64')
        ]

        when:
        def multiReq = new MultiPlatformBuildRequest(req, platforms)

        then:
        !multiReq.singlePlatform
        multiReq.multiPlatform
        multiReq.platforms.size() == 2
        multiReq.platformString == 'linux/amd64,linux/arm64'
        
        and:
        def platformRequests = multiReq.createPlatformRequests()
        platformRequests.size() == 2
        platformRequests[0].platform.toString() == 'linux/amd64'
        platformRequests[1].platform.toString() == 'linux/arm64'
    }

    def 'should create default platform when empty' () {
        given:
        def req = new BuildRequest(
                containerId: 'c168dba125e28777',
                containerFile: 'FROM ubuntu:latest',
                workspace: Path.of('/work/foo'),
                platform: ContainerPlatform.of('linux/amd64'),
                targetImage: 'quay.io/wave:c168dba125e28777',
                cacheRepository: 'reg.io/wave/build/cache',
        )

        when:
        def multiReq = new MultiPlatformBuildRequest(req, null)

        then:
        multiReq.singlePlatform
        multiReq.platforms.size() == 1
        multiReq.platforms[0] == ContainerPlatform.DEFAULT
    }
}