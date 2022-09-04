package io.seqera.wave.controller

import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

import io.micronaut.context.ApplicationContext
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.annotation.MockBean
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.seqera.wave.core.RoutePath
import io.seqera.wave.model.ContentType
import io.seqera.wave.service.ContainerRequestData
import io.seqera.wave.service.ContainerTokenService
import io.seqera.wave.service.builder.BuildResult
import io.seqera.wave.service.builder.ContainerBuildService
import io.seqera.wave.service.builder.ContainerBuildServiceImpl
import io.seqera.wave.storage.DigestStore
import io.seqera.wave.storage.MemoryStorage
import io.seqera.wave.storage.Storage
import io.seqera.wave.test.DockerRegistryContainer
import io.seqera.wave.util.Base32
import jakarta.inject.Inject
/**
 *
 * @author Jorge Aguilera <jorge.aguilera@seqera.io>
 */
@MicronautTest(environments = ['test', 'h2', 'build'])
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class CustomImageControllerTest extends Specification implements DockerRegistryContainer {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    @Shared
    ApplicationContext applicationContext

    BuildResult expected

    boolean resolveImageAsync = false

    @MockBean(ContainerBuildService)
    ContainerBuildService containerBuildService(){
        Mock(ContainerBuildServiceImpl){
            buildResult(RoutePath) >> {
                if( !expected )
                    return null
                resolveImageAsync == false ? CompletableFuture.completedFuture(expected) : CompletableFuture.supplyAsync({
                    sleep(2*1000)
                    expected
                })
            }
        }
    }

    @MockBean(ContainerTokenService)
    ContainerTokenService containerTokenService(){
        Mock(ContainerTokenService){
            getRequest(_) >> new ContainerRequestData(
                    null,
                    null,
                    "${Base32.encode('library/hello-world'.bytes)}",
                    "FROM busybox",
                    null,
                    null)
        }
    }

    @MockBean(Storage)
    Storage storageService(){
        Mock(MemoryStorage){
            getManifest(_) >> {
                !expected ? Optional.empty() :
                Optional.of(new DigestStore(){
                    @Override
                    byte[] getBytes() {
                        return 'Hi'.bytes
                    }

                    @Override
                    String getMediaType() {
                        return 'application/octect-stream'
                    }

                    @Override
                    String getDigest() {
                        return 'Hi'
                    }
                })
            }
        }
    }

    def setupSpec() {
        initRegistryContainer(applicationContext)
    }

    void 'should fails head manifest when no image'() {
        given:
        expected = null // no image requested previously

        when:
        HttpRequest request = HttpRequest.HEAD("/v2/wt/1234/${Base32.encode('library/hello-world'.bytes)}/manifests/latest").headers({h->
            h.add('Accept', ContentType.DOCKER_MANIFEST_V2_TYPE)
            h.add('Accept', ContentType.DOCKER_MANIFEST_V1_JWS_TYPE)
            h.add('Accept', MediaType.APPLICATION_JSON)
        })
        HttpResponse<String> response = client.toBlocking().exchange(request,String)
        then:
        thrown(HttpClientResponseException)
    }

    void 'should retrieve head manifest when image is completed'() {
        given:
        expected = new BuildResult('xyz', 0, 'OK', Instant.now())

        when:
        HttpRequest request = HttpRequest.HEAD("/v2/wt/1234/${Base32.encode('library/hello-world'.bytes)}/manifests/latest").headers({h->
            h.add('Accept', ContentType.DOCKER_MANIFEST_V2_TYPE)
            h.add('Accept', ContentType.DOCKER_MANIFEST_V1_JWS_TYPE)
            h.add('Accept', MediaType.APPLICATION_JSON)
        })
        HttpResponse<String> response = client.toBlocking().exchange(request,String)
        then:
        response.status == HttpStatus.OK
    }

    void 'should wait for head manifest when image is under construction'() {
        given:
        expected = new BuildResult('xyz', 0, 'OK', Instant.now())
        resolveImageAsync = true

        when:
        HttpRequest request = HttpRequest.HEAD("/v2/wt/1234/${Base32.encode('library/hello-world'.bytes)}/manifests/latest").headers({h->
            h.add('Accept', ContentType.DOCKER_MANIFEST_V2_TYPE)
            h.add('Accept', ContentType.DOCKER_MANIFEST_V1_JWS_TYPE)
            h.add('Accept', MediaType.APPLICATION_JSON)
        })
        HttpResponse<String> response = client.toBlocking().exchange(request,String)
        then:
        response.status == HttpStatus.OK
    }

}