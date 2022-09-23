package io.seqera.wave.auth

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.core.annotation.Nullable
import io.seqera.wave.model.ContainerCoordinates
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * Compute Docker auth config json to authentication Kaniko build 
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@Singleton
@CompileStatic
class DockerAuthService {

    @Inject
    private RegistryLookupService lookupService

    @Inject
    private RegistryCredentialsProvider credentialsProvider

    String credentialsConfigJson(String dockerFile, String buildRepo, String cacheRepo, @Nullable Long userId, @Nullable Long workspaceId) {
        final repos = new HashSet(10)
        repos.addAll(findRepositories(dockerFile))
        if( buildRepo )
            repos.add(buildRepo)
        if( cacheRepo )
            repos.add(cacheRepo)
        return credsJson(repos, userId, workspaceId)
    }

    protected String credsJson(Set<String> repositories, Long userId, Long workspaceId) {
        final result = new StringBuilder()
        for( String repo : repositories ) {
            final path = ContainerCoordinates.parse(repo)
            final info = lookupService.lookup(path.registry)
            final creds = credentialsProvider.getUserCredentials(path, userId, workspaceId)
            log.debug "Build credentials for repository: $repo => $creds"
            if( !creds )
                continue
            final encode = "${creds.username}:${creds.password}".getBytes().encodeBase64()
            if( result.size() )
                result.append(',')
            result.append("\"${info.index}\":{\"auth\":\"$encode\"}")
        }
        return result.size() ? """{"auths":{$result}}""" : null
    }

    static protected Set<String> findRepositories(String dockerfile) {
        final result = new HashSet()
        if( !dockerfile )
            return result
        for( String line : dockerfile.readLines()) {
            if( !line.trim().toLowerCase().startsWith('from '))
                continue
            def repo = line.trim().substring(5)
            def p = repo.indexOf(' ')
            if( p!=-1 )
                repo = repo.substring(0,p)
            result.add(repo)
        }
        return result
    }

}