package io.seqera.docker

import java.nio.file.Path
import javax.validation.constraints.NotBlank

import groovy.transform.Memoized
import groovy.util.logging.Slf4j
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Context
import io.micronaut.context.annotation.Value
import io.micronaut.http.MediaType
import io.micronaut.inject.qualifiers.Qualifiers
import io.seqera.auth.LoginValidator
import io.seqera.config.TowerConfiguration
import io.seqera.storage.Storage
import io.seqera.storage.DigestStore
import io.seqera.RouteHelper
import io.seqera.auth.DockerAuthProvider
import io.seqera.config.Registry
import io.seqera.proxy.ProxyClient
import jakarta.inject.Singleton

/**
 * @author : jorge <jorge.aguilera@seqera.io>
 *
 */
@Slf4j
@Singleton
@Context
class ContainerService {

    Storage storage
    ApplicationContext applicationContext

    ContainerService(Storage storage, ApplicationContext applicationContext, TowerConfiguration towerConfiguration) {
        this.storage = storage
        this.applicationContext = applicationContext
        this.arch = towerConfiguration.arch
        this.layerPath = towerConfiguration.layerPath
    }

    private String arch

    private String layerPath

    private ContainerScanner scanner(ProxyClient proxyClient) {
        return new ContainerScanner()
                .withArch(arch)
                .withLayerConfig(Path.of(layerPath))
                .withStorage(storage)
                .withClient(proxyClient)
    }

    private ProxyClient client(Registry registry, String image) {
        DockerAuthProvider authProvider = findAuthProvider(registry.name)
        new ProxyClient(registry.host, image, authProvider)
    }

    boolean validateUser(String registry, String user, String password){
        String host
        try {
            Registry registryBean = findRegistryWithName(registry)
            host = registryBean.host
        }catch( Exception ie){
            host = registry
        }
        LoginValidator validator = new LoginValidator()
        validator.login(user, password, host)
    }


    DigestStore handleManifest(RouteHelper.Route route, Map<String,List<String>> headers){
        final Registry registry = findRegistry(route.registry)
        assert registry

        ProxyClient proxyClient = client(registry, route.image)

        final digest = scanner(proxyClient).resolve(route.image, route.reference, headers)
        if( digest == null )
            throw new IllegalStateException("Missing digest for request: $route")

        final req = "/v2/$route.image/manifests/$digest"
        final entry = storage.getManifest(req).orElseThrow( ()->
                new IllegalStateException("Missing cached entry for request: $req"))
        return entry
    }

    DelegateResponse handleRequest(RouteHelper.Route route, Map<String,List<String>> headers){
        final Registry registry = findRegistry(route.registry)

        ProxyClient proxyClient = client(registry, route.image)
        final resp = proxyClient.getStream(route.path, headers)

        String contentType = headers.find {
            it.key.toLowerCase().equals("content-type")
        }?.value?.first() ?: MediaType.APPLICATION_OCTET_STREAM
        String digest = route.path.split(':').last()

        if( !storage.containsBlob(route.path) ){
            final asyncResp = proxyClient.getStream(route.path, headers)
            storage.asyncSaveBlob(route.path, asyncResp.body(), contentType, digest)
        }

        new DelegateResponse(
                statusCode: resp.statusCode(),
                headers: resp.headers().map(),
                body: resp.body()
        )
    }

    private Registry findRegistryWithName(String name){
        applicationContext.getBean(Registry, Qualifiers.byName(name.replaceAll("\\.","-")))
    }

    @Memoized
    private Registry defaultRegistry() {
        Collection<Registry> registries = applicationContext.getBeansOfType(Registry)
        // fallback docker
        def result  = registries.find { it.host.contains('docker.io') }
        if (result) {
            log.debug "Using docker.io as default registry ==> $result"
            return result
        }
        // just find the first
        result = registries.first()
        log.debug "Unable to find docker.io registry config - Fallback to first => $result"
        return result
    }

    @Memoized
    private Registry findRegistry(String name){
        if( !name )
            return defaultRegistry()

        try {
            final result = findRegistryWithName(name)
            log.debug "Find registry by name: $name => $result"
            return result
        }
        catch( Exception e) {
            Collection<Registry> registries = applicationContext.getBeansOfType(Registry)
            def result = registries.find { name && it.name == name }
            if (result) {
                log.debug "Find typed registry by name: $name => $result"
                return result
            }
            throw new IllegalArgumentException("Unable to find a registry configuration by name: $name")
        }
    }

    private DockerAuthProvider findAuthProvider(String name){
        try{
            applicationContext.getBean(DockerAuthProvider, Qualifiers.byName(name.replaceAll("\\.","-")))
        }catch( Exception e) {
            Collection<DockerAuthProvider> auths = applicationContext.getBeansOfType(DockerAuthProvider)
            auths.first()
        }
    }

    static class DelegateResponse{
        int statusCode
        Map<String,List<String>> headers
        InputStream body
    }

}
