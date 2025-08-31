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

package io.seqera.wave.controller

import spock.lang.Specification

import io.seqera.wave.api.SubmitContainerTokenRequest
import io.seqera.wave.configuration.BuildConfig
import io.seqera.wave.core.ContainerPlatform
import io.seqera.wave.service.builder.MultiPlatformBuildRequest
import io.seqera.wave.service.inclusion.ContainerInclusionService
import io.seqera.wave.service.validation.ValidationService
import io.seqera.wave.tower.PlatformId

/**
 * Test for multi-platform build request handling in ContainerController
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class ContainerControllerMultiPlatformTest extends Specification {

    def 'should create multi-platform build request' () {
        given:
        def buildConfig = new BuildConfig(
                defaultBuildRepository: 'wave/build',
                defaultCacheRepository: 'wave/cache',
                buildWorkspace: '/tmp'
        )
        def validationService = Mock(ValidationService) {
            isCustomRepo(_) >> false
        }
        def controller = new ContainerController(
                buildConfig: buildConfig,
                validationService: validationService,
                inclusionService: Mock(ContainerInclusionService)
        )

        when:
        def req = new SubmitContainerTokenRequest(
                containerFile: 'RlJPTSB1YnVudHU6bGF0ZXN0',  // FROM ubuntu:latest (base64)
                containerPlatform: 'linux/amd64,linux/arm64'
        )
        def multiReq = controller.makeMultiPlatformBuildRequest(req, PlatformId.NULL, '127.0.0.1')

        then:
        multiReq != null
        multiReq.platforms.size() == 2
        multiReq.platforms[0].toString() == 'linux/amd64'
        multiReq.platforms[1].toString() == 'linux/arm64'
        multiReq.platformString == 'linux/amd64,linux/arm64'
        !multiReq.singlePlatform
        multiReq.multiPlatform
    }

    def 'should create single platform build request for backward compatibility' () {
        given:
        def buildConfig = new BuildConfig(
                defaultBuildRepository: 'wave/build',
                defaultCacheRepository: 'wave/cache',
                buildWorkspace: '/tmp'
        )
        def validationService = Mock(ValidationService) {
            isCustomRepo(_) >> false
        }
        def controller = new ContainerController(
                buildConfig: buildConfig,
                validationService: validationService,
                inclusionService: Mock(ContainerInclusionService)
        )

        when:
        def req = new SubmitContainerTokenRequest(
                containerFile: 'RlJPTSB1YnVudHU6bGF0ZXN0',  // FROM ubuntu:latest (base64)
                containerPlatform: 'linux/amd64'
        )
        def multiReq = controller.makeMultiPlatformBuildRequest(req, PlatformId.NULL, '127.0.0.1')

        then:
        multiReq != null
        multiReq.platforms.size() == 1
        multiReq.platforms[0].toString() == 'linux/amd64'
        multiReq.platformString == 'linux/amd64'
        multiReq.singlePlatform
        !multiReq.multiPlatform
    }

    def 'should create default platform when none specified' () {
        given:
        def buildConfig = new BuildConfig(
                defaultBuildRepository: 'wave/build',
                defaultCacheRepository: 'wave/cache',
                buildWorkspace: '/tmp'
        )
        def validationService = Mock(ValidationService) {
            isCustomRepo(_) >> false
        }
        def controller = new ContainerController(
                buildConfig: buildConfig,
                validationService: validationService,
                inclusionService: Mock(ContainerInclusionService)
        )

        when:
        def req = new SubmitContainerTokenRequest(
                containerFile: 'RlJPTSB1YnVudHU6bGF0ZXN0',  // FROM ubuntu:latest (base64)
                containerPlatform: null  // No platform specified
        )
        def multiReq = controller.makeMultiPlatformBuildRequest(req, PlatformId.NULL, '127.0.0.1')

        then:
        multiReq != null
        multiReq.platforms.size() == 1
        multiReq.platforms[0] == ContainerPlatform.DEFAULT
        multiReq.platformString == 'linux/amd64'
        multiReq.singlePlatform
        !multiReq.multiPlatform
    }
}