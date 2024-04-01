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

import io.micronaut.context.ApplicationContext
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.seqera.wave.configuration.SpackConfig
import io.seqera.wave.core.ContainerPlatform
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@MicronautTest
class DockerBuildStrategyTest extends Specification {

    def 'should get docker command' () {
        given:
        def props = [
                'wave.build.spack.secretKeyFile':'/host/spack/key',
                'wave.build.spack.secretMountPath':'/opt/spack/key'  ]
        def ctx = ApplicationContext.run(props)
        and:
        def service = ctx.getBean(DockerBuildStrategy)
        def spackConfig = ctx.getBean(SpackConfig)
        and:
        def work = Path.of('/work/foo')
        when:
        def cmd = service.cmdForKaniko(work, null, null, null)
        then:
        cmd == ['docker',
                'run',
                '--rm',
                '-v', '/work/foo:/work/foo',
                'gcr.io/kaniko-project/executor:v1.19.2']

        when:
        cmd = service.cmdForKaniko(work, Path.of('/foo/creds.json'), null, ContainerPlatform.of('arm64'))
        then:
        cmd == ['docker',
                'run',
                '--rm',
                '-v', '/work/foo:/work/foo',
                '-v', '/foo/creds.json:/kaniko/.docker/config.json:ro',
                '--platform', 'linux/arm64',
                'gcr.io/kaniko-project/executor:v1.19.2']

        when:
        cmd = service.cmdForKaniko(work, Path.of('/foo/creds.json'), spackConfig, null)
        then:
        cmd == ['docker',
                'run',
                '--rm',
                '-v', '/work/foo:/work/foo',
                '-v', '/foo/creds.json:/kaniko/.docker/config.json:ro',
                '-v', '/host/spack/key:/opt/spack/key:ro',
                'gcr.io/kaniko-project/executor:v1.19.2']

        cleanup:
        ctx.close()
    }

    def 'should get kaniko build command' () {
        given:
        def ctx = ApplicationContext.run()
        def service = ctx.getBean(DockerBuildStrategy)
        and:
        def creds = Path.of('/work/creds.json')
        and:
        def req = new BuildRequest(
                workDir: Path.of('/work/foo/89fb83ce6ec8627b'),
                platform: ContainerPlatform.of('linux/amd64'),
                targetImage: 'repo:89fb83ce6ec8627b',
                cacheRepository: 'reg.io/wave/build/cache' )
        when:
        def cmd = service.buildCmd(req, creds)
        then:
        cmd == ['docker',
                'run',
                '--rm',
                '-v', '/work/foo/89fb83ce6ec8627b:/work/foo/89fb83ce6ec8627b',
                '-v', '/work/creds.json:/kaniko/.docker/config.json:ro',
                '--platform', 'linux/amd64',
                'gcr.io/kaniko-project/executor:v1.19.2',
                '--dockerfile', '/work/foo/89fb83ce6ec8627b/Containerfile',
                '--context', '/work/foo/89fb83ce6ec8627b/context',
                '--destination', 'repo:89fb83ce6ec8627b',
                '--cache=true',
                '--custom-platform', 'linux/amd64',
                '--cache-repo', 'reg.io/wave/build/cache' ]

        cleanup:
        ctx.close()
    }

    def 'should disable compress-caching' () {
        given:
        def ctx = ApplicationContext.run(['wave.build.compress-caching': false])
        def service = ctx.getBean(DockerBuildStrategy)
        and:
        def req = new BuildRequest(
                workDir: Path.of('/work/foo/89fb83ce6ec8627b'),
                platform: ContainerPlatform.of('linux/amd64'),
                targetImage: 'repo:89fb83ce6ec8627b',
                cacheRepository: 'reg.io/wave/build/cache' )
        when:
        def cmd = service.launchCmd(req)
        then:
        cmd == [
                '--dockerfile', '/work/foo/89fb83ce6ec8627b/Containerfile',
                '--context', '/work/foo/89fb83ce6ec8627b/context',
                '--destination', 'repo:89fb83ce6ec8627b',
                '--cache=true',
                '--custom-platform', 'linux/amd64',
                '--cache-repo', 'reg.io/wave/build/cache',
                '--compressed-caching=false' ]

        cleanup:
        ctx.close()
    }

    def 'should get singularity build command' () {
        given:
        def props = [
                'wave.build.spack.secretKeyFile':'/host/spack/key',
                'wave.build.spack.secretMountPath':'/opt/spack/key'  ]
        def ctx = ApplicationContext.run(props)
        def service = ctx.getBean(DockerBuildStrategy)
        SpackConfig spackConfig = ctx.getBean(SpackConfig)
        service.setSpackConfig(spackConfig)
        and:
        def creds = Path.of('/work/creds.json')
        and:
        def req = new BuildRequest(
                workDir: Path.of('/work/foo/d4869cc39b8d7d55'),
                platform: ContainerPlatform.of('linux/amd64'),
                targetImage: 'oras://repo:d4869cc39b8d7d55',
                cacheRepository: 'reg.io/wave/build/cache',
                format: BuildFormat.SINGULARITY,
                isSpackBuild: true )
        when:
        def cmd = service.buildCmd(req, creds)
        then:
        cmd == ['docker',
                'run',
                '--rm',
                '--privileged',
                '--entrypoint', '',
                '-v', '/work/foo/d4869cc39b8d7d55:/work/foo/d4869cc39b8d7d55',
                '-v', '/work/creds.json:/root/.singularity/docker-config.json:ro',
                '-v', '/work/singularity-remote.yaml:/root/.singularity/remote.yaml:ro',
                '-v', '/host/spack/key:/opt/spack/key:ro',
                '--platform', 'linux/amd64',
                'quay.io/singularity/singularity:v3.11.4-slim',
                'sh',
                '-c',
                'singularity build image.sif /work/foo/d4869cc39b8d7d55/Containerfile && singularity push image.sif oras://repo:d4869cc39b8d7d55'
        ]
        
        cleanup:
        ctx.close()
    }

    def 'should get singularity build command for arm64 architecture' () {
        given:
        def props = [
                'wave.build.spack.secretKeyFile':'/host/spack/key',
                'wave.build.spack.secretMountPath':'/opt/spack/key'  ]
        def ctx = ApplicationContext.run(props)
        def service = ctx.getBean(DockerBuildStrategy)
        SpackConfig spackConfig = ctx.getBean(SpackConfig)
        service.setSpackConfig(spackConfig)
        and:
        def creds = Path.of('/work/creds.json')
        and:
        def req = new BuildRequest(
                workDir: Path.of('/work/foo/9c68af894bb2419c'),
                platform: ContainerPlatform.of('linux/arm64'),
                targetImage: 'oras://repo:9c68af894bb2419c',
                cacheRepository: 'reg.io/wave/build/cache',
                format: BuildFormat.SINGULARITY,
                isSpackBuild: true )
        when:
        def cmd = service.buildCmd(req, creds)
        then:
        cmd == ['docker',
                'run',
                '--rm',
                '--privileged',
                '--entrypoint', '',
                '-v', '/work/foo/9c68af894bb2419c:/work/foo/9c68af894bb2419c',
                '-v', '/work/creds.json:/root/.singularity/docker-config.json:ro',
                '-v', '/work/singularity-remote.yaml:/root/.singularity/remote.yaml:ro',
                '-v', '/host/spack/key:/opt/spack/key:ro',
                '--platform', 'linux/arm64',
                'quay.io/singularity/singularity:v3.11.4-slim-arm64',
                'sh',
                '-c',
                'singularity build image.sif /work/foo/9c68af894bb2419c/Containerfile && singularity push image.sif oras://repo:9c68af894bb2419c'
        ]

        cleanup:
        ctx.close()
    }
}
