package io.seqera.wave.service.builder

import java.nio.file.Path
import java.util.concurrent.Future

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import io.seqera.wave.util.DigestFunctions
/**
 * Model a container builder result
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@EqualsAndHashCode
@ToString(includeNames = true, includePackage = false)
@CompileStatic
class BuildRequest {

    final String id
    final String dockerFile
    final String condaFile
    final Path workDir
    final String targetImage
    volatile Future<BuildResult> result

    BuildRequest(String dockerFile, Path workspace, String repo, String condaFile) {
        this.id = DigestFunctions.digest(dockerFile).replace('sha256:','')
        this.dockerFile = dockerFile
        this.condaFile = condaFile
        this.targetImage = "${repo}:${id}"
        this.workDir = workspace.resolve(id).toAbsolutePath()
    }

}
