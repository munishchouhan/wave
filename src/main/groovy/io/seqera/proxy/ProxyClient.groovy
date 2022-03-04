package io.seqera.proxy

import io.seqera.auth.DockerAuthProvider

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.time.Duration

import groovy.util.logging.Slf4j
import io.seqera.controller.RegHelper

/**
 *
 * https://www.baeldung.com/java-9-http-client
 * https://openjdk.java.net/groups/net/httpclient/recipes.html
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
class ProxyClient {

    private String image

    DockerAuthProvider authProvider
    private String registryName
    private HttpClient httpClient

    ProxyClient(String registryName, String image, DockerAuthProvider authProvider) {
        this.image = image
        this.registryName = registryName
        this.authProvider = authProvider
        init()
    }

    private void init() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build()
    }

    private URI makeUri(String path) {
        assert path.startsWith('/'), "Request past should start with a slash character -- offending path: $path"
        URI.create("https://$registryName$path")
    }

    HttpResponse<String> getString(String path, Map<String,List<String>> headers=null) {
        try {
            return get( makeUri(path), headers, HttpResponse.BodyHandlers.ofString() )
        }
        catch (BadResponseException e) {
            return ErrResponse<String>.forString(e.message, e.request)
        }
    }

    HttpResponse<InputStream> getStream(String path, Map<String,List<String>> headers=null) {
        try {
            return get( makeUri(path), headers, HttpResponse.BodyHandlers.ofInputStream() )
        }
        catch (BadResponseException e) {
            return ErrResponse.forStream(e.message, e.request)
        }
    }

    HttpResponse<byte[]> getBytes(String path, Map<String,List<String>> headers=null) {
        try {
            return get( makeUri(path), headers, HttpResponse.BodyHandlers.ofByteArray() )
        }
        catch (BadResponseException e) {
            return ErrResponse.forByteArray(e.message, e.request)
        }
    }


    private static final List<String> SKIP_HEADERS = ['host', 'connection', 'authorization']

    private void copyHeaders(Map<String,List<String>> headers, HttpRequest.Builder builder) {
        if( !headers )
            return

        for( Map.Entry<String,List<String>> entry : headers )  {
            if( entry.key.toLowerCase() in SKIP_HEADERS )
                continue
            for( String val : entry.value )
                builder.header(entry.key, val)
        }
    }

    private static final int[] REDIRECT_CODES = [301, 302, 307, 308]

    def <T> HttpResponse<T> get(URI origin, Map<String,List<String>> headers, BodyHandler<T> handler) {
        final host = origin.getHost()
        final visited = new HashSet<URI>(10)
        def target = origin
        int redirectCount = 0
        int authRetry = 0
        while( true ) {
            // add authorization header ONLY when the target host
            // is different from the origin host, otherwise it can
            // cause an error when the redirection target uses a different
            // auth mechanism e.g. signed url
            // https://github.com/rkt/rkt/issues/2266#issuecomment-211326020
            final authorize = host == target.getHost()
            final result = get0(target, headers, handler, authorize)
            // add to visited URL
            visited.add(target)
            // check the result code
            if( result.statusCode()==401 && (authRetry++)<2 && host==target.host ) {
                // clear the token to force refreshing it
                authProvider.cleanTokenFor(image)
                continue
            }
            if( result.statusCode() in REDIRECT_CODES  ) {
                final redirect = result.headers().firstValue('location').orElse(null)
                log.trace "Redirecting (${++redirectCount}) $target ==> $redirect ${RegHelper.dumpHeaders(result.headers())}"
                if( !redirect ) {
                    final msg = "Missing `Location` header for request URI '$target' ― origin request '$origin'"
                    throw new BadResponseException(msg, result.request())
                }
                target = new URI(redirect)
                if( target in visited ) {
                    final msg = "Redirect location already visited: $redirect ― origin request '$origin'"
                    throw new BadResponseException(msg, result.request())
                }
                if( visited.size()>=10 ) {
                    final msg = "Redirect location already visited: $redirect ― origin request '$origin'"
                    throw new BadResponseException(msg, result.request())
                }
                // go head with with the redirection
                continue
            }
            return result
        }

    }

    private <T> HttpResponse<T> get0(URI uri, Map<String,List<String>> headers, BodyHandler<T> handler, boolean authorize) {
        final builder = HttpRequest.newBuilder(uri) .GET()
        copyHeaders(headers, builder)
        if( authorize ) {
            // add authorisation header
            String token = authProvider.getTokenFor(image)
            builder.setHeader("Authorization", "Bearer ${token}")
        }
        // build the request
        final request = builder.build()
        // send it
        return httpClient.send(request, handler)
    }

    HttpResponse<Void> head(String path, Map<String,List<String>> headers=null) {
        return head(makeUri(path), headers)
    }

    HttpResponse<Void> head(URI uri, Map<String,List<String>> headers) {
        def result = head0(uri, headers)
        if( result.statusCode()==401 ) {
            // clear the token to force refreshing it
            authProvider.cleanTokenFor(image)
            result = head0(uri, headers)
        }
        return result
    }

    HttpResponse<Void> head0(URI uri, Map<String,List<String>> headers) {
        // A HEAD request is a GET request without a message body
        // https://zetcode.com/java/httpclient/
        final builder = HttpRequest.newBuilder(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
        // copy headers 
        copyHeaders(headers, builder)
        // add authorisation header
        String token = authProvider.getTokenFor(image)
        builder.setHeader("Authorization", "Bearer ${token}")
        // build the request
        final request = builder.build()
        // send it 
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding())
    }


}