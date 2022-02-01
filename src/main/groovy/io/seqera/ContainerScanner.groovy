package io.seqera

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class ContainerScanner {

    private ProxyClient client
    private Path compressedLayerPath
    private Path uncompressedLayerPath
    private String arch

    private Cache cache

    {
        compressedLayerPath = Paths.get('foo.tar.gzip')
        uncompressedLayerPath = Paths.get('foo.tar')
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

    ContainerScanner withLayer(Path path) {
        final name = path.getFileName().toString()
        if( !name.endsWith('.tar.gzip') )
            throw new IllegalArgumentException("Layer name should ends with .tar.gzip")
        this.compressedLayerPath = path
        this.uncompressedLayerPath = path.resolveSibling( name.replace('.gzip', '') )
        return this
    }

    protected Path getCompressedLayer() {
        return compressedLayerPath
    }

    protected Path getUncompressedLayer() {
        return uncompressedLayerPath
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

        // get target manifest
        final targetDigest = findTargetDigest(manifestsList, Mock.MANIFEST_MIME)
        final resp3 = client.getString("/v2/$imageName/manifests/$targetDigest", headers)
        final imageManifest = resp3.body()
        log.debug "Image $imageName:$tag => image manifest=\n${JsonOutput.prettyPrint(imageManifest)}"

        // find the image config digest
        final configDigest = findImageConfigDigest(imageManifest)

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

        // update the manifests list with the new digest
        // returns the manifests list digest
        final newListDigest = updateManifestsList(imageName, manifestsList, targetDigest, newManifestDigest)
        log.debug "==> new list digest: $newListDigest"

        return newListDigest
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
        assert compressedLayerPath

        // store the layer blob in the cache
        final type = "application/vnd.docker.image.rootfs.diff.tar.gzip"
        final buffer = Files.readAllBytes(compressedLayerPath)
        final digest = RegHelper.digest(buffer)
        final size = Files.size(compressedLayerPath) as int
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

    protected String updateImageConfig(String imageName, String imageConfig) {
        assert uncompressedLayerPath

        final buffer = Files.readAllBytes(uncompressedLayerPath)
        final newLayer = RegHelper.digest(buffer)

        // turn the json string into a json map
        // and append the new layer
        final manifest = (Map) new JsonSlurper().parseText(imageConfig)
        final rootfs = manifest.rootfs as Map
        final layers = rootfs.diff_ids as List
        layers.add( newLayer )

        // turn the updated manifest into a json
        final newConfig = JsonOutput.toJson(manifest)

        // add to the cache
        final digest = RegHelper.digest(newConfig)
        final path = "/v2/$imageName/blobs/$digest"
        cache.put(path, newConfig.bytes, Mock.IMAGE_CONFIG_MIME, digest)

        // return the updated image manifest digest
        return digest
    }

    @CompileDynamic
    protected String findTargetDigest(String body, String mediaType ) {
        final json = (Map) new JsonSlurper().parseText(body)
        final record = json.manifests.find( { record ->  record.mediaType == mediaType && record.platform.os=='linux' && record.platform.architecture==arch } )
        final result = record.digest
        log.trace "Find target digest arch: $arch ==> digest: $result"
        return result
    }

}