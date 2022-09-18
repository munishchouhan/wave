package io.seqera.wave.core

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import groovy.transform.ToString
import io.seqera.wave.service.ContainerRequestData

import static io.seqera.wave.WaveDefault.DOCKER_IO

/**
 * Model a container registry route path
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Canonical
@ToString(includePackage = false, includeNames = true)
@CompileStatic
class RoutePath implements ContainerPath {

    static final private List<String> ALLOWED_TYPES = ['manifests','blobs']

    final String type
    final String registry
    final String image
    final String reference
    final String path
    final ContainerRequestData request

    boolean isManifest() { type=='manifests' }
    boolean isBlob() { type=='blobs' }
    boolean isTag() { reference && !isDigest() }
    boolean isDigest() { reference && reference.startsWith('sha256:') }

    String getRepository() { "$registry/$image" }

    String getTargetContainer() { "$registry/$image:$reference" }

    static RoutePath v2path(String type, String registry, String image, String ref, ContainerRequestData request=null) {
        assert type in ALLOWED_TYPES, "Unknown container path type: '$type'"
        new RoutePath(type, registry ?: DOCKER_IO, image, ref, "/v2/$image/$type/$ref", request)
    }

    static RoutePath empty() {
        new RoutePath(null, null, null, null, null, null)
    }
}
