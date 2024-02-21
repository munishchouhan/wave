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

package io.seqera.wave.service.persistence.impl

import spock.lang.Specification

import java.nio.file.Path
import java.time.Duration
import java.time.Instant

import io.micronaut.context.ApplicationContext
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.seqera.wave.api.ContainerConfig
import io.seqera.wave.api.SubmitContainerTokenRequest
import io.seqera.wave.core.ContainerPlatform
import io.seqera.wave.core.ContainerDigestPair
import io.seqera.wave.service.builder.BuildFormat
import io.seqera.wave.service.scan.ScanVulnerability
import io.seqera.wave.service.ContainerRequestData
import io.seqera.wave.service.builder.BuildEvent
import io.seqera.wave.service.builder.BuildRequest
import io.seqera.wave.service.builder.BuildResult
import io.seqera.wave.service.persistence.CondaPackageRecord
import io.seqera.wave.service.persistence.WaveBuildRecord
import io.seqera.wave.service.persistence.WaveContainerRecord
import io.seqera.wave.service.persistence.WaveScanRecord
import io.seqera.wave.test.SurrealDBTestContainer
import io.seqera.wave.tower.PlatformId
import io.seqera.wave.tower.User
/**
 * @author : jorge <jorge.aguilera@seqera.io>
 *
 */
class SurrealPersistenceServiceTest extends Specification implements SurrealDBTestContainer {

    ApplicationContext applicationContext

    String getSurrealDbURL() {
        "http://$surrealHostName:$surrealPort"
    }

    def setup() {
        restartDb()
        applicationContext = ApplicationContext.run([
                        surreal:['default': [
                                user     : 'root',
                                password : 'root',
                                ns       : 'test',
                                db       : 'test',
                                url      : surrealDbURL,
                                'init-db': false
                        ]]]
        , 'test', 'surrealdb')
    }

    def cleanup() {
        applicationContext.close()
    }

    void "can connect"() {
        given:
        HttpClient httpClient = HttpClient.create(new URL(surrealDbURL))

        when:
        def str = httpClient.toBlocking()
                .retrieve(
                        HttpRequest.POST("/sql", "SELECT * FROM count()")
                                .headers(['ns': 'test', 'db': 'test', 'accept':'application/json'])
                                .basicAuth('root', 'root'), Map<String, String>)

        then:
        str.result.first() == 1
    }

    void "can insert an async build"() {
        given:
        final String dockerFile = """\
            FROM quay.io/nextflow/bash
            RUN echo "Look ma' building 🐳🐳 on the fly!" > /hello.txt
            ENV NOW=${System.currentTimeMillis()}
            """
        final String condaFile = """
            echo "Look ma' building 🐳🐳 on the fly!" > /hello.txt
        """
        HttpClient httpClient = HttpClient.create(new URL(surrealDbURL))
        SurrealPersistenceService storage = applicationContext.getBean(SurrealPersistenceService)
        BuildRequest request = new BuildRequest(dockerFile,
                Path.of("."), "buildrepo", condaFile, null, BuildFormat.DOCKER, PlatformId.NULL, null, null,
                ContainerPlatform.of('amd64'),'{auth}', null, null, "127.0.0.1", null)
        BuildResult result = new BuildResult(request.id, -1, "ok", Instant.now(), Duration.ofSeconds(3))
        BuildEvent event = new BuildEvent(request, result)
        WaveBuildRecord build = WaveBuildRecord.fromEvent(event)

        when:
        storage.initializeDb()

        storage.saveBuild(build)

        sleep 100 //as we are using async, let database a while to store the item
        then:
        def map = httpClient.toBlocking()
                .retrieve(
                        HttpRequest.GET("/key/wave_build")
                                .headers([
                                        'ns'          : 'test',
                                        'db'          : 'test',
                                        'User-Agent'  : 'micronaut/1.0',
                                        'Accept': 'application/json'])
                                .basicAuth('root', 'root'), Map<String, Object>)
        map.result.size()
        map.result.first().requestIp == '127.0.0.1'
    }

    void "can't insert a build but ends without error"() {
        given:
        SurrealPersistenceService storage = applicationContext.getBean(SurrealPersistenceService)
        WaveBuildRecord build = new WaveBuildRecord(
                buildId: 'test',
                dockerFile: 'test',
                condaFile: 'test',
                targetImage: 'test',
                userName: 'test',
                userEmail: 'test',
                userId: 1,
                requestIp: '127.0.0.1',
                startTime: Instant.now(),
                duration: Duration.ofSeconds(1),
                exitStatus: 0 )

        when:
        surrealContainer.stop()

        storage.saveBuild(build)

        sleep 100 //as we are using async, let database a while to store the item
        then:
        true
    }

    void "an event insert a build"() {
        given:
        HttpClient httpClient = HttpClient.create(new URL(surrealDbURL))
        def storage = applicationContext.getBean(SurrealPersistenceService)
        storage.initializeDb()
        final service = applicationContext.getBean(SurrealPersistenceService)
        BuildRequest request = new BuildRequest("test", Path.of("."), "test", "test", null, BuildFormat.DOCKER, Mock(PlatformId), null, null, ContainerPlatform.of('amd64'),'{auth}', "test", null, "127.0.0.1", null)
        BuildResult result = new BuildResult(request.id, 0, "content", Instant.now(), Duration.ofSeconds(1))
        BuildEvent event = new BuildEvent(request, result)

        when:
        service.onBuildEvent(event)
        sleep 100 //as we are using async, let database a while to store the item
        then:
        def map = httpClient.toBlocking()
                .retrieve(
                        HttpRequest.GET("/key/wave_build")
                                .headers([
                                        'ns'          : 'test',
                                        'db'          : 'test',
                                        'User-Agent'  : 'micronaut/1.0',
                                        'Accept': 'application/json'])
                                .basicAuth('root', 'root'), Map<String, Object>)
        map.result.size()
        map.result.first().requestIp == '127.0.0.1'
    }

    void "an event is not inserted if no database"() {
        given:
        surrealContainer.stop()
        final service = applicationContext.getBean(SurrealPersistenceService)
        BuildRequest request = new BuildRequest("test", Path.of("."), "test", "test", null, BuildFormat.DOCKER, Mock(PlatformId), null, null, ContainerPlatform.of('amd64'),'{auth}', "test", null, "127.0.0.1", null)
        BuildResult result = new BuildResult(request.id, 0, "content", Instant.now(), Duration.ofSeconds(1))
        BuildEvent event = new BuildEvent(request, result)

        when:
        service.onBuildEvent(event)
        sleep 100 //as we are using async, let database a while to store the item
        then:
        true
    }

    def 'should load a build record' () {
        given:
        final persistence = applicationContext.getBean(SurrealPersistenceService)
        final request = new BuildRequest(
                'FROM foo:latest',
                Path.of("/some/path"),
                "buildrepo",
                'conda::recipe',
                null,
                BuildFormat.DOCKER,
                PlatformId.NULL,
                null,
                null,
                ContainerPlatform.of('amd64'),
                '{auth}',
                'docker.io/my/repo',
                null,
                "1.2.3.4",
                null )
        final result = new BuildResult(request.id, -1, "ok", Instant.now(), Duration.ofSeconds(3))
        final event = new BuildEvent(request, result)
        final record = WaveBuildRecord.fromEvent(event)

        and:
        persistence.saveBuildBlocking(record)

        when:
        def loaded = persistence.loadBuild(record.buildId)
        then:
        loaded == record
    }

    def 'should load a request record' () {
        given:
        def persistence = applicationContext.getBean(SurrealPersistenceService)
        and:
        def TOKEN = '123abc'
        def cfg = new ContainerConfig(entrypoint: ['/opt/fusion'])
        def req = new SubmitContainerTokenRequest(
                towerEndpoint: 'https://tower.nf',
                towerWorkspaceId: 100,
                containerConfig: cfg,
                containerPlatform: ContainerPlatform.of('amd64'),
                buildRepository: 'build.docker.io',
                cacheRepository: 'cache.docker.io',
                fingerprint: 'xyz',
                timestamp: Instant.now().toString()
        )
        def user = new User(id: 1, userName: 'foo', email: 'foo@gmail.com')
        def data = new ContainerRequestData(new PlatformId(user,100), 'hello-world' )
        def wave = "wave.io/wt/$TOKEN/hello-world"
        def addr = "100.200.300.400"
        def exp = Instant.now().plusSeconds(3600)
        and:
        def request = new WaveContainerRecord(req, data, wave, addr, exp)

        and:
        persistence.saveContainerRequest(TOKEN, request)
        and:
        sleep 200  // <-- the above request is async, give time to save it
        
        when:
        def loaded = persistence.loadContainerRequest(TOKEN)
        then:
        loaded == request


        // should update the record
        when:
        persistence.updateContainerRequest(TOKEN, new ContainerDigestPair('111', '222'))
        and:
        sleep 200
        then:
        def updated = persistence.loadContainerRequest(TOKEN)
        and:
        updated.sourceDigest == '111'
        updated.sourceImage == request.sourceImage
        and:
        updated.waveDigest == '222'
        updated.waveImage == request.waveImage

    }

    def 'should save a scan and load a result' () {
        given:
        def persistence = applicationContext.getBean(SurrealPersistenceService)
        def NOW = Instant.now()
        def SCAN_ID = 'a1'
        def BUILD_ID = '100'
        def CVE1 = new ScanVulnerability('cve-1', 'x1', 'title1', 'package1', 'version1', 'fixed1', 'url1')
        def CVE2 = new ScanVulnerability('cve-2', 'x2', 'title2', 'package2', 'version2', 'fixed2', 'url2')
        def CVE3 = new ScanVulnerability('cve-3', 'x3', 'title3', 'package3', 'version3', 'fixed3', 'url3')
        def scanRecord = new WaveScanRecord(SCAN_ID, BUILD_ID, NOW, Duration.ofSeconds(10), 'SUCCEEDED', [CVE1, CVE2, CVE3])
        when:
        persistence.createScanRecord(new WaveScanRecord(SCAN_ID, BUILD_ID, NOW))
        persistence.updateScanRecord(scanRecord)
        then:
        def result = persistence.loadScanRecord(SCAN_ID)
        and:
        result == scanRecord
        and:
        def scan = persistence.loadScanResult(SCAN_ID)
        scan.status == 'SUCCEEDED'
        scan.buildId == BUILD_ID
        scan.vulnerabilities == scanRecord.vulnerabilities

        when:
        def SCAN_ID2 = 'b2'
        def BUILD_ID2 = '102'
        def scanRecord2 = new WaveScanRecord(SCAN_ID2, BUILD_ID2, NOW, Duration.ofSeconds(20), 'FAILED', [CVE1])
        and:
        persistence.createScanRecord(new WaveScanRecord(SCAN_ID2, BUILD_ID2, NOW))
        // should save the same CVE into another build
        persistence.updateScanRecord(scanRecord2)
        then:
        def result2 = persistence.loadScanRecord(SCAN_ID2)
        and:
        result2 == scanRecord2
    }

    def 'should save and find conda packages' () {
        given:
        def persistence = applicationContext.getBean(SurrealPersistenceService)
        def record1 = new CondaPackageRecord('channel1', 'package1', '1.1')
        def record2 = new CondaPackageRecord('channel2', 'package2', '1.2')
        def record3 = new CondaPackageRecord('channel3', 'package3', '1.3')
        def defaultLimit = 100

        when:
        persistence.saveCondaPackages([record1, record2, record3])

        then:
        sleep(100)
        persistence.findCondaPackage(null,null, defaultLimit) == [
                new CondaPackageRecord('wave_conda_package:⟨channel1::package1=1.1⟩', 'channel1', 'package1', '1.1'),
                new CondaPackageRecord('wave_conda_package:⟨channel2::package2=1.2⟩', 'channel2', 'package2', '1.2'),
                new CondaPackageRecord('wave_conda_package:⟨channel3::package3=1.3⟩', 'channel3', 'package3', '1.3')
        ]
        and: 'when searching for a specific package'
        persistence.findCondaPackage('package1',null, defaultLimit) == [
                new CondaPackageRecord('wave_conda_package:⟨channel1::package1=1.1⟩', 'channel1', 'package1', '1.1')
        ]
        and: 'when searching for a specific package and version'
        persistence.findCondaPackage('package2=1',null, defaultLimit) == [
                new CondaPackageRecord('wave_conda_package:⟨channel2::package2=1.2⟩', 'channel2', 'package2', '1.2')
        ]
        and: 'when searching for a specific channel'
        persistence.findCondaPackage('channel3',null, defaultLimit) == [
                new CondaPackageRecord('wave_conda_package:⟨channel3::package3=1.3⟩', 'channel3', 'package3', '1.3')
        ]
        and: 'when searching from multiple channels'
        persistence.findCondaPackage(null, ['channel1','channel3'], defaultLimit) == [
                new CondaPackageRecord('wave_conda_package:⟨channel1::package1=1.1⟩', 'channel1', 'package1', '1.1'),
                new CondaPackageRecord('wave_conda_package:⟨channel3::package3=1.3⟩', 'channel3', 'package3', '1.3')
        ]
        and: 'when asking for limited number of results'
        persistence.findCondaPackage(null, null, 1) == [
                new CondaPackageRecord('wave_conda_package:⟨channel1::package1=1.1⟩', 'channel1', 'package1', '1.1')
        ]
    }
    

    def 'should return correct where clause for wave_conda_package table' () {
        expect:
        SurrealPersistenceService.getCondaFetcherFilter(CRITERIA, CHANNELS) == FILTER
        where:
        CRITERIA        | CHANNELS      | FILTER
        null            | null          | ''
        ''              | []            | ''
        'foo'           | null          | "WHERE name ~ 'foo' OR channel ~ 'foo'"
        'foo::bar'      | null          | "WHERE name ~ 'bar' AND channel ~ 'foo'"
        'bar=1.0'       | null          | "WHERE name ~ 'bar' AND version ~ '1.0'"
        'foo::bar=1.0'  | null          | "WHERE name ~ 'bar' AND channel ~ 'foo' AND version ~ '1.0'"
        'foo'           | ['bar']       | "WHERE name ~ 'foo' AND channel IN ['bar']"
        'foo::bar'      | ['bar']       | "WHERE name ~ 'bar' AND channel IN ['bar']"
        'foo::bar=1.0'  | ['bar']       | "WHERE name ~ 'bar' AND channel IN ['bar'] AND version ~ '1.0'"
    }

    def 'should return correct tokens from search string' () {
        expect:
        SurrealPersistenceService.parseSearchString(CRITERIA) == TOKENS
        where:
        CRITERIA       | TOKENS
        'foo*'         | ['foo', null, null]
        'foo'          | ['foo', null, null]
        'foo::bar'     | ['bar', 'foo', null]
        'bar=1.0'      | ['bar', null, '1.0']
        'foo::bar=1.0' | [ 'bar', 'foo', '1.0']
    }
}
