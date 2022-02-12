package io.seqera

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.util.logging.Slf4j
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class ContainerScanner {

    private ProxyClient client
    private Path layerConfigPath
    private String arch

    private Cache cache

    {
        withLayerConfig(Paths.get('build/layers/layer.json'))
    }

    ContainerScanner withCache(Cache cache) {
        this.cache = cache
        return this
    }

    ContainerScanner withClient(ProxyClient client) {
        this.client = client
        return this
    }

    ContainerScanner withArch(String arch) {
        assert arch, "Missing 'arch' parameter"
        assert arch in ['x86_64', 'amd64', 'arm64'], "Unsupported architecture: $arch"

        this.arch = arch == 'x86_64' ? 'amd64' : arch
        return this
    }

    ContainerScanner withLayerConfig(Path path) {
        log.debug "Layer config path: $path"
        this.layerConfigPath = path
        if( !Files.exists(path )) {
            throw new IllegalArgumentException("Specific config path does not exist: $path")
        }
        return this
    }

    Path getLayerConfigPath() {
        return layerConfigPath
    }

    protected LayerConfig getLayerConfig() {
        assert layerConfigPath, "Missing layer config path"
        final attrs = Files.readAttributes(layerConfigPath, BasicFileAttributes)
        // note file size and last modified timestamp are only needed
        // to invalidate the memoize cache
        return createConfig(layerConfigPath.toFile(), attrs.size(), attrs.lastModifiedTime().toMillis())
    }

    @Memoized
    protected LayerConfig createConfig(File path, long size, long lastModified) {
        final layerConfig = new JsonSlurper().parse(path) as LayerConfig
        if( !layerConfig.append?.location )
            throw new IllegalArgumentException("Missing layer tar path")
        if( !layerConfig.append?.gzipDigest )
            throw new IllegalArgumentException("Missing layer gzip digest")
        if( !layerConfig.append?.tarDigest )
            throw new IllegalArgumentException("Missing layer tar digest")

        if( !layerConfig.append.gzipDigest.startsWith('sha256:') )
            throw new IllegalArgumentException("Missing layer gzip digest should start with the 'sha256:' prefix -- offending value: $layerConfig.append.gzipDigest")
        if( !layerConfig.append.tarDigest.startsWith('sha256:') )
            throw new IllegalArgumentException("Missing layer tar digest should start with the 'sha256:' prefix -- offending value: $layerConfig.append.tarDigest")

        if( !Files.exists(layerConfig.append.locationPath) )
            throw new IllegalArgumentException("Missing layer tar file: $layerConfig.append.locationPath")

        log.debug "Layer info: path=$layerConfig.append.location; gzip-checksum=$layerConfig.append.gzipDigest; tar-checksum=$layerConfig.append.tarDigest"
        return layerConfig
    }

    String resolve(String imageName, String tag, Map<String,List<String>> headers) {
        assert client, "Missing client"
        // resolve image tag to digest
        final resp1 = client.head("/v2/$imageName/manifests/$tag", headers)
        final digest = resp1.headers().firstValue('docker-content-digest')
        log.debug "Image $imageName:$tag => digest=$digest"
        if( digest.isEmpty() )
            return null

        // get manifest list for digest
        final resp2 = client.getString("/v2/$imageName/manifests/${digest.get()}", headers)
        final manifestsList = resp2.body()
        log.debug "Image $imageName:$tag => manifests list=\n${JsonOutput.prettyPrint(manifestsList)}"

//        // get target manifest
//        final targetDigest = findTargetDigest(manifestsList)
//        final resp3 = client.getString("/v2/$imageName/manifests/$targetDigest", headers)
//        final imageManifest = resp3.body()
//        log.debug "Image $imageName:$tag => image manifest=\n${JsonOutput.prettyPrint(imageManifest)}"
//
//        // find the image config digest
//        final configDigest = findImageConfigDigest(imageManifest)

        final manifestResult = findImageManifestAndDigest(manifestsList, imageName, tag, headers)
        final imageManifest = manifestResult.first
        final configDigest = manifestResult.second
        final targetDigest = manifestResult.third

        // fetch the image config
        final resp4 = client.getString("/v2/$imageName/blobs/$configDigest", headers)
        final imageConfig = resp4.body()
        log.debug "Image $imageName:$tag => image config=\n${JsonOutput.prettyPrint(imageConfig)}"

        // update the image config adding the new layer
        final newConfigDigest = updateImageConfig(imageName, imageConfig)
        log.debug "==> new config digest: $newConfigDigest"

        // update the image manifest adding a new layer
        // returns the updated image manifest digest
        final newManifestDigest = updateImageManifest(imageName, imageManifest, newConfigDigest)
        log.debug "==> new image digest: $newManifestDigest"

        if( !targetDigest ) {
            return newManifestDigest
        }
        else {
            // update the manifests list with the new digest
            // returns the manifests list digest
            final newListDigest = updateManifestsList(imageName, manifestsList, targetDigest, newManifestDigest)
            log.debug "==> new list digest: $newListDigest"

            return newListDigest
        }

    }

    protected Tuple3<String,String,String> findImageManifestAndDigest(String manifest, String imageName, String tag, Map<String,List<String>> headers) {

        def json = new JsonSlurper().parseText(manifest) as Map
        // check the response mime, can be either
        // 1. application/vnd.docker.distribution.manifest.list.v2+json ==> image list
        // 2. application/vnd.docker.distribution.manifest.v2+json  ==> image manifest

        def targetDigest = null
        def media = json.mediaType
        if( media == Mock.MANIFEST_LIST_MIME ) {
            // get target manifest
            targetDigest = findTargetDigest(json)
            final resp3 = client.getString("/v2/$imageName/manifests/$targetDigest", headers)
            manifest = resp3.body()
            log.debug "Image $imageName:$tag => image manifest=\n${JsonOutput.prettyPrint(manifest)}"
            // parse the new manifest
            json = new JsonSlurper().parseText(manifest) as Map
            media = json.mediaType
        }

        if( media == Mock.MANIFEST_MIME ) {
            // find the image config digest
            final configDigest = findImageConfigDigest(manifest)
            return new Tuple3(manifest, configDigest, targetDigest)
        }
        else {
            throw new IllegalArgumentException("Unexpected media type for request '$imageName:$tag' - offending value: $media")
        }
        
    }

    protected String updateManifestsList(String imageName, String manifestsList, String targetDigest, String newDigest) {
        final updated = manifestsList.replace(targetDigest, newDigest)
        final result = RegHelper.digest(updated)
        final type = Mock.MANIFEST_LIST_MIME
        // make sure the manifest was updated
        if( manifestsList==updated )
            throw new IllegalArgumentException("Unable to find target digest '$targetDigest' into image list manifest")
        // store in the cache
        cache.put("/v2/$imageName/manifests/$result", updated.bytes, type, result)
        // return the updated manifests list digest
        return result
    }

    protected Map layerBlob(String image) {

        // store the layer blob in the cache
        final type = "application/vnd.docker.image.rootfs.diff.tar.gzip"
        final buffer = Files.readAllBytes(layerConfig.append.locationPath)
        final digest = RegHelper.digest(buffer)
        final size = Files.size(layerConfig.append.locationPath) as int
        if( digest != layerConfig.append.gzipDigest )
            throw new IllegalArgumentException("Layer gzip computed digest does not match with expected digest -- path=$layerConfig.append.locationPath; computed=$digest; expected: $layerConfig.append.gzipDigest")
        final path = "/v2/$image/blobs/$digest"
        cache.put(path, buffer, type, digest)

        final result = new HashMap()
        result."mediaType" = type
        result."size" = size
        result."digest" = digest
        return result
    }

    /**
     * @param imageManifest hold the image config json. It has the following structure
     * <pre>
     *     {
     *      "schemaVersion": 2,
     *      "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
     *      "config": {
     *              "mediaType": "application/vnd.docker.container.image.v1+json",
     *              "size": 1469,
     *              "digest": "sha256:feb5d9fea6a5e9606aa995e879d862b825965ba48de054caab5ef356dc6b3412"
     *          },
     *      "layers": [
     *          {
     *              "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
     *              "size": 2479,
     *              "digest": "sha256:2db29710123e3e53a794f2694094b9b4338aa9ee5c40b930cb8063a1be392c54"
     *          }
     *      ]
     *    }
     * </pre>
     * @return
     */
    @CompileDynamic
    protected String findImageConfigDigest(String imageManifest) {
        final json = (Map) new JsonSlurper().parseText(imageManifest)
        return json.config.digest
    }

    protected String updateImageManifest(String imageName, String imageManifest, String newImageConfigDigest) {

        // get the layer blob
        final newLayer = layerBlob(imageName)

        // turn the json string into a json map
        // and append the new layer
        final manifest = (Map) new JsonSlurper().parseText(imageManifest)
        (manifest.layers as List).add( newLayer )

        // update the config digest
        (manifest.config as Map).digest = newImageConfigDigest

        // turn the updated manifest into a json
        final newManifest = JsonOutput.prettyPrint(JsonOutput.toJson(manifest))

        // add to the cache
        final digest = RegHelper.digest(newManifest)
        final path = "/v2/$imageName/manifests/$digest"
        cache.put(path, newManifest.bytes, Mock.MANIFEST_MIME, digest)

        // return the updated image manifest digest
        return digest
    }

    protected String getFirst(value) {
        if( !value )
            return null
        if( value instanceof List ) {
            if( value.size()>1 ) log.warn "Invalid  Entrypoint value: $value -- Only the first array element will be taken"
            return value.get(0)
        }
        if( value instanceof String )
            return value
        log.warn "Invalid Entrypoint type: ${value.getClass().getName()} -- Offending value: $value"
        return null
    }

    protected List<String> appendEnv(List<String> env, List<String> newEntries) {
        if( !newEntries )
            return env
        return env
                ? (env + newEntries)
                : newEntries
    }

    protected String updateImageConfig(String imageName, String imageConfig) {

        final newLayer = layerConfig.append.tarDigest

        // turn the json string into a json map
        // and append the new layer
        final manifest = new JsonSlurper().parseText(imageConfig) as Map
        final rootfs = manifest.rootfs as Map
        final layers = rootfs.diff_ids as List
        layers.add( newLayer )

        // update the image config
        final config = manifest.config as Map
        final entryChain = getFirst(config.Entrypoint)
        if( layerConfig.entrypoint ) {
            config.Entrypoint = layerConfig.entrypoint
        }
        if( layerConfig.cmd ) {
            config.Cmd = layerConfig.cmd
        }
        if( layerConfig.workingDir ) {
            config.WorkingDir = layerConfig.workingDir
        }
        if( layerConfig.env ) {
            config.Env = appendEnv(config.Env as List, layerConfig.env)
        }
        if( entryChain ) {
            config.Env = appendEnv( config.Env as List, [ "XREG_ENTRY_CHAIN="+entryChain ] )
        }

        // turn the updated manifest into a json
        final newConfig = JsonOutput.toJson(manifest)

        // add to the cache
        final digest = RegHelper.digest(newConfig)
        final path = "/v2/$imageName/blobs/$digest"
        cache.put(path, newConfig.bytes, Mock.IMAGE_CONFIG_MIME, digest)

        // return the updated image manifest digest
        return digest
    }


    protected String findTargetDigest(String body ) {
        findTargetDigest((Map) new JsonSlurper().parseText(body))
    }

    @CompileDynamic
    protected String findTargetDigest(Map json) {
        final mediaType = Mock.MANIFEST_MIME
        final record = json.manifests.find( { record ->  record.mediaType == mediaType && record.platform.os=='linux' && record.platform.architecture==arch } )
        final result = record.digest
        log.trace "Find target digest arch: $arch ==> digest: $result"
        return result
    }

}
