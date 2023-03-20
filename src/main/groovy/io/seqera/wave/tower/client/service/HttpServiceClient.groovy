package io.seqera.wave.tower.client.service

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import javax.annotation.PostConstruct

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.http.HttpMethod
import io.seqera.wave.service.pairing.socket.msg.ProxyHttpRequest
import io.seqera.wave.service.pairing.socket.msg.ProxyHttpResponse
import io.seqera.wave.util.HttpRetryable
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * Tower service client
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @author Jordi Deu-Pons <jordi@seqera.io>
 */
@Slf4j
@Singleton
@CompileStatic
class HttpServiceClient extends AbstractServiceClient {

    @Inject
    private HttpRetryable httpRetryable

    private HttpClient client

    @PostConstruct
    void init() {
        this.client = HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(httpRetryable.config().connectTimeout)
                                .build()
    }

    @Override
    CompletableFuture<ProxyHttpResponse> sendAsync(String service, String endpoint, ProxyHttpRequest request) {
        final httpResponse = httpRetryable.sendAsync(client, buildHttpRequest(request), HttpResponse.BodyHandlers.ofString())
        return httpResponse.thenCompose { CompletableFuture.completedFuture(new ProxyHttpResponse(
                msgId: request.msgId,
                status: it.statusCode(),
                body: it.body(),
                headers: it.headers().map()
        ))}
    }

    private static HttpRequest buildHttpRequest(ProxyHttpRequest proxyRequest) {

        final builder = HttpRequest
                .newBuilder()
                .uri(URI.create(proxyRequest.uri))

        final method = HttpMethod.parse(proxyRequest.method)
        switch (method) {
            case HttpMethod.GET: builder.GET(); break
            case HttpMethod.DELETE: builder.DELETE(); break
            default:
                builder.method(method.toString(), HttpRequest.BodyPublishers.ofString(proxyRequest.body))
        }

        if (proxyRequest.bearerAuth)
            builder.header('Authorization', "Bearer ${proxyRequest.bearerAuth}")

        if (proxyRequest.headers) {
            proxyRequest.headers.each { header ->
                header.value.each {
                    builder.header(header.key, it)
                }
            }
        }

        return builder.build()
    }

}
