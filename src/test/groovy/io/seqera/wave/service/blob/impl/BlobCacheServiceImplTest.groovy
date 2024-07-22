/*
 *  Wave, containers provisioning service
 *  Copyright (c) 2024, Seqera Labs
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
package io.seqera.wave.service.blob.impl

import spock.lang.Specification

import java.util.concurrent.ExecutorService

import io.seqera.wave.configuration.BlobCacheConfig
import io.seqera.wave.core.RegistryProxyService
import io.seqera.wave.core.RoutePath
import io.seqera.wave.model.ContainerCoordinates
import io.seqera.wave.service.blob.BlobCacheInfo
import io.seqera.wave.service.blob.BlobStore
import io.seqera.wave.test.AwsS3TestContainer
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */

class BlobCacheServiceImplTest extends Specification implements AwsS3TestContainer{

    def 'should get s5cmd cli' () {
        given:
        def service = new BlobCacheServiceImpl(blobConfig: new BlobCacheConfig(storageBucket: 's3://store/blobs/'))
        def route = RoutePath.v2manifestPath(ContainerCoordinates.parse('ubuntu@sha256:aabbcc'))

        when:
        def result = service.s5cmd(route, Mock(BlobCacheInfo))
        then:
        result == ['s5cmd', '--json', 'pipe',  's3://store/blobs/docker.io/v2/library/ubuntu/manifests/sha256:aabbcc']

        when:
        result = service.s5cmd(route, BlobCacheInfo.create('http://foo', [:], ['Content-Type':['foo'], 'Cache-Control': ['bar']]))
        then:
        result == ['s5cmd', '--json', 'pipe', '--content-type', 'foo', '--cache-control', 'bar', 's3://store/blobs/docker.io/v2/library/ubuntu/manifests/sha256:aabbcc']

    }

    def 'should get s5cmd cli with custom endpoint' () {
        given:
        def config = new BlobCacheConfig( storageBucket: 's3://store/blobs/', storageEndpoint: 'https://foo.com' )
        def service = new BlobCacheServiceImpl(blobConfig: config)
        def route = RoutePath.v2manifestPath(ContainerCoordinates.parse('ubuntu@sha256:aabbcc'))

        when:
        def result = service.s5cmd(route, new BlobCacheInfo())
        then:
        result == ['s5cmd', '--endpoint-url', 'https://foo.com', '--json', 'pipe', 's3://store/blobs/docker.io/v2/library/ubuntu/manifests/sha256:aabbcc']
    }

    def 'should get transfer command' () {
        given:
        def proxyService = Mock(RegistryProxyService)
        def service = new BlobCacheServiceImpl( blobConfig: new BlobCacheConfig(storageBucket: 's3://store/blobs/'), proxyService: proxyService )
        def route = RoutePath.v2manifestPath(ContainerCoordinates.parse('ubuntu@sha256:aabbcc'))
        and:
        def response = ['content-type': ['something']]
        def blobCache = BlobCacheInfo.create('http://foo', ['foo': ['one']], response)
        
        when:
        def result = service.transferCommand(route, blobCache)
        then:
        proxyService.curl(route, [foo:'one']) >> ['curl', '-X', 'GET', 'http://foo']
        and:
        result == [
                'sh',
                '-c',
                "curl -X GET 'http://foo' | s5cmd --json pipe --content-type something 's3://store/blobs/docker.io/v2/library/ubuntu/manifests/sha256:aabbcc'"
        ]
    }

    def 'should return blob size when blob exists'() {
        given:
        def bucket = 's3://my-cache-bucket'
        def expectedSize = 1024
        def s3Client = Mock(S3Client)
        def blobCacheService = new BlobCacheServiceImpl(s3Client: s3Client, blobConfig: new BlobCacheConfig(storageBucket: bucket))
        and:
        def route = Mock(RoutePath) {
            getTargetPath() >> 'docker.io/repo/container/latest'
        }
        and:
        final request =
                HeadObjectRequest.builder()
                        .bucket('my-cache-bucket')
                        .key('docker.io/repo/container/latest')
                        .build()

        when:
        def size = blobCacheService.getBlobSize(route)

        then:
        1 * s3Client.headObject(_) >> HeadObjectResponse.builder().contentLength(expectedSize).build()
        and:
        size == expectedSize
    }

    def 'should return zero when blob does not exist'() {
        given:
        def bucket = 's3://my-cache-bucket'
        def s3Client = Mock(S3Client)
        def blobCacheService = new BlobCacheServiceImpl(s3Client: s3Client, blobConfig: new BlobCacheConfig(storageBucket: bucket))
        and:
        def route = Mock(RoutePath) {
            getTargetPath() >> 'docker.io/repo/container/latest'
        }
        and:
        final request =
                HeadObjectRequest.builder()
                        .bucket('my-cache-bucket')
                        .key('docker.io/repo/container/latest')
                        .build()

        when:
        def size = blobCacheService.getBlobSize(route)

        then:
        1 * s3Client.headObject(request) >> { throw S3Exception.builder().message('Not Found').build() }
        and:
        size == -1L
    }

    def 'should delete blob when blob exists'() {
        given:
        def bucket = 's3://my-cache-bucket/base/dir'
        def s3Client = Mock(S3Client)
        def blobCacheService = new BlobCacheServiceImpl(s3Client: s3Client, blobConfig: new BlobCacheConfig(storageBucket: bucket))
        and:
        def route = Mock(RoutePath) {
            getTargetPath() >> 'docker.io/repo/container/latest'
        }
        and:
        def request = DeleteObjectRequest.builder()
                .bucket('my-cache-bucket')
                .key('base/dir/docker.io/repo/container/latest')
                .build()

        when:
        blobCacheService.deleteBlob(route)
        then:
        1 * s3Client.deleteObject(request) >> { }
    }

    def 'should return failed BlobCacheInfo when blob size mismatch'() {
        given:
        def executor = Mock(ExecutorService)
        def s3Client = Mock(S3Client)
        s3Client.headObject(_) >> HeadObjectResponse.builder().contentLength(1234L).build()
        def blobStore = Mock(BlobStore)
        def blobCacheService = new BlobCacheServiceImpl(s3Client: s3Client, blobConfig: new BlobCacheConfig(storageBucket: 's3://store/blobs/'), blobStore: blobStore, executor: executor, )
        def route = RoutePath.v2manifestPath(ContainerCoordinates.parse('ubuntu@sha256:aabbcc'))
        def info = BlobCacheInfo.create('http://foo', [:], ['Content-Type':['foo'], 'Cache-Control': ['bar'], 'Content-Length': ['4321']])
        info = info.completed(0, 'Blob uploaded')

        when:
        def result = blobCacheService.checkUploadedBlobSize(info, route)

        then:
        !result.succeeded()
        result.logs == "Mismatch cache size for object http://foo"
    }

    def 'should return succeeded BlobCacheInfo when blob size matches'() {
        given:
        def executor = Mock(ExecutorService)
        def s3Client = Mock(S3Client)
        s3Client.headObject(_) >> HeadObjectResponse.builder().contentLength(4321L).build()
        def blobStore = Mock(BlobStore)
        def blobCacheService = new BlobCacheServiceImpl(s3Client: s3Client, blobConfig: new BlobCacheConfig(storageBucket: 's3://store/blobs/'), blobStore: blobStore, executor: executor)
        def route = RoutePath.v2manifestPath(ContainerCoordinates.parse('ubuntu@sha256:aabbcc'))
        def info = BlobCacheInfo.create('http://foo', [:], ['Content-Type':['foo'], 'Cache-Control': ['bar'], 'Content-Length': ['4321']])
        info = info.completed(0, 'Blob uploaded')

        when:
        def result = blobCacheService.checkUploadedBlobSize(info, route)

        then:
        result.succeeded()
        result.logs == "Blob uploaded"
    }

}
