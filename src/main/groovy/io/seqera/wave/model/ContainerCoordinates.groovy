package io.seqera.wave.model

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import io.seqera.wave.core.ContainerPath
import static io.seqera.wave.WaveDefault.DOCKER_IO
/**
 * Model a container image coordinates
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Canonical
@CompileStatic
class ContainerCoordinates implements ContainerPath {

    final String registry
    final String image
    final String reference

    String getRepository() { "$registry/$image" }

    String getTargetContainer() {
        return registry + '/' + getImageAndTag()
    }

    String getImageAndTag() {
        if( !reference ) return image
        final sep = reference.startsWith('sha256:') ? '@' : ':'
        return image + sep + reference
    }

    static ContainerCoordinates parse(String path) {

        final coordinates = path.tokenize('/')

        String ref
        def last = coordinates.size()-1
        int pos
        if( (pos=coordinates[last].indexOf('@'))!=-1 || (pos=coordinates[last].indexOf(':'))!=-1 ) {
            def name = coordinates[last]
            ref = name.substring(pos+1)
            coordinates[last] = name.substring(0,pos)
        }
        else {
           ref = 'latest'
        }

        // check if it's registry host name
        String reg=null
        if( coordinates[0].contains('.') || coordinates[0].contains(':') ) {
            reg = coordinates[0]; coordinates.remove(0)
        }
        // default to docker registry
        reg ?= DOCKER_IO
        if( !isValidRegistry(reg) || !ref )
            throw new IllegalArgumentException("Invalid container image name: $path")

        // add default library prefix to docker images
        if( coordinates.size()==1 && reg==DOCKER_IO ) {
            coordinates.add(0,'library')
        }

        final image = coordinates.join('/')
        return new ContainerCoordinates(reg, image, ref)
    }

    static boolean isValidRegistry(String name) {
        if( !name )
            return false
        final p = name.indexOf(':')
        return p==-1 || name.substring(p+1).isInteger()
    }
}
