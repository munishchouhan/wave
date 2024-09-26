/*
 *  Wave, containers provisioning service
 *  Copyright (c) 2023-2024, Seqera Labs
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.seqera.wave.controller

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.annotation.PostConstruct

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.context.annotation.Value
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Error
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.server.util.HttpClientAddressResolver
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.AuthorizationException
import io.micronaut.security.rules.SecurityRule
import io.seqera.wave.api.ImageNameStrategy
import io.seqera.wave.api.ScanMode
import io.seqera.wave.api.SubmitContainerTokenRequest
import io.seqera.wave.api.SubmitContainerTokenResponse
import io.seqera.wave.configuration.BuildConfig
import io.seqera.wave.core.ContainerPlatform
import io.seqera.wave.core.RegistryProxyService
import io.seqera.wave.exception.BadRequestException
import io.seqera.wave.exception.NotFoundException
import io.seqera.wave.exchange.DescribeWaveContainerResponse
import io.seqera.wave.model.ContainerCoordinates
import io.seqera.wave.ratelimit.AcquireRequest
import io.seqera.wave.ratelimit.RateLimiterService
import io.seqera.wave.service.ContainerRequestData
import io.seqera.wave.service.UserService
import io.seqera.wave.service.builder.BuildRequest
import io.seqera.wave.service.builder.BuildTrack
import io.seqera.wave.service.builder.ContainerBuildService
import io.seqera.wave.service.builder.FreezeService
import io.seqera.wave.service.inclusion.ContainerInclusionService
import io.seqera.wave.service.inspect.ContainerInspectService
import io.seqera.wave.service.mirror.ContainerMirrorService
import io.seqera.wave.service.mirror.MirrorRequest
import io.seqera.wave.service.pairing.PairingService
import io.seqera.wave.service.pairing.socket.PairingChannel
import io.seqera.wave.service.persistence.PersistenceService
import io.seqera.wave.service.persistence.WaveContainerRecord
import io.seqera.wave.service.token.ContainerTokenService
import io.seqera.wave.service.token.TokenData
import io.seqera.wave.service.validation.ValidationService
import io.seqera.wave.tower.PlatformId
import io.seqera.wave.tower.User
import io.seqera.wave.tower.auth.JwtAuth
import io.seqera.wave.tower.auth.JwtAuthStore
import io.seqera.wave.util.DataTimeUtils
import io.seqera.wave.util.LongRndKey
import jakarta.inject.Inject
import static io.micronaut.http.HttpHeaders.WWW_AUTHENTICATE
import static io.seqera.wave.service.builder.BuildFormat.DOCKER
import static io.seqera.wave.service.builder.BuildFormat.SINGULARITY
import static io.seqera.wave.service.pairing.PairingService.TOWER_SERVICE
import static io.seqera.wave.util.ContainerHelper.checkContainerSpec
import static io.seqera.wave.util.ContainerHelper.condaFileFromRequest
import static io.seqera.wave.util.ContainerHelper.containerFileFromPackages
import static io.seqera.wave.util.ContainerHelper.decodeBase64OrFail
import static io.seqera.wave.util.ContainerHelper.makeContainerId
import static io.seqera.wave.util.ContainerHelper.makeResponseV1
import static io.seqera.wave.util.ContainerHelper.makeResponseV2
import static io.seqera.wave.util.ContainerHelper.makeTargetImage
import static io.seqera.wave.util.ContainerHelper.patchPlatformEndpoint
import static java.util.concurrent.CompletableFuture.completedFuture
/**
 * Implement a controller to receive container token requests
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
@Controller("/")
@ExecuteOn(TaskExecutors.IO)
class ContainerController {

    @Inject HttpClientAddressResolver addressResolver
    @Inject ContainerTokenService tokenService
    @Inject UserService userService
    @Inject JwtAuthStore jwtAuthStore

    @Inject
    @Value('${wave.allowAnonymous}')
    Boolean allowAnonymous

    @Inject
    @Value('${wave.server.url}')
    String serverUrl

    @Inject
    @Value('${tower.endpoint.url:`https://api.cloud.seqera.io`}')
    String towerEndpointUrl

    @Value('${wave.scan.enabled:false}')
    boolean scanEnabled

    @Inject
    BuildConfig buildConfig

    @Inject
    ContainerBuildService buildService

    @Inject
    ContainerInspectService inspectService

    @Inject
    RegistryProxyService registryProxyService

    @Inject
    PersistenceService persistenceService

    @Inject
    ValidationService validationService

    @Inject
    PairingService pairingService

    @Inject
    PairingChannel pairingChannel

    @Inject
    FreezeService freezeService

    @Inject
    ContainerInclusionService inclusionService

    @Inject
    @Nullable
    RateLimiterService rateLimiterService

    @Inject
    private ContainerMirrorService mirrorService

    @PostConstruct
    private void init() {
        log.info "Wave server url: $serverUrl; allowAnonymous: $allowAnonymous; tower-endpoint-url: $towerEndpointUrl; default-build-repo: $buildConfig.defaultBuildRepository; default-cache-repo: $buildConfig.defaultCacheRepository; default-public-repo: $buildConfig.defaultPublicRepository"
    }

    @Deprecated
    @Post('/container-token')
    @ExecuteOn(TaskExecutors.IO)
    CompletableFuture<HttpResponse<SubmitContainerTokenResponse>> getToken(HttpRequest httpRequest, SubmitContainerTokenRequest req) {
        return getContainerImpl(httpRequest, req, false)
    }

    @Post('/v1alpha2/container')
    @ExecuteOn(TaskExecutors.IO)
    CompletableFuture<HttpResponse<SubmitContainerTokenResponse>> getTokenV2(HttpRequest httpRequest, SubmitContainerTokenRequest req) {
        return getContainerImpl(httpRequest, req, true)
    }

    protected CompletableFuture<HttpResponse<SubmitContainerTokenResponse>> getContainerImpl(HttpRequest httpRequest, SubmitContainerTokenRequest req, boolean v2) {
        // patch platform endpoint
        req.towerEndpoint = patchPlatformEndpoint(req.towerEndpoint)

        // validate request
        validateContainerRequest(req)
        validateMirrorRequest(req, v2)

        // this is needed for backward compatibility with old clients
        if( !req.towerEndpoint ) {
            req.towerEndpoint = towerEndpointUrl
        }

        // anonymous access
        if( !req.towerAccessToken ) {
            return completedFuture(handleRequest(httpRequest, req, PlatformId.NULL, v2))
        }

        // first check if the service is registered
        final registration = pairingService.getPairingRecord(TOWER_SERVICE, req.towerEndpoint)
        if( !registration )
            throw new BadRequestException("Missing pairing record for Tower endpoint '$req.towerEndpoint'")

        // store the jwt record only the very first time it has been
        // to avoid overridden a newer refresh token that may have 
        final auth = JwtAuth.of(req)
        if( auth.refresh )
            jwtAuthStore.storeIfAbsent(auth)

        // find out the user associated with the specified tower access token
        return userService
                .getUserByAccessTokenAsync(registration.endpoint, auth)
                .thenApply((User user) -> handleRequest(httpRequest, req, PlatformId.of(user,req), v2))
    }

    protected HttpResponse<SubmitContainerTokenResponse> handleRequest(HttpRequest httpRequest, SubmitContainerTokenRequest req, PlatformId identity, boolean v2) {
        if( !identity && !allowAnonymous )
            throw new BadRequestException("Missing user access token")
        if( v2 && req.containerFile && req.packages )
            throw new BadRequestException("Attribute `containerFile` and `packages` conflicts each other")
        if( v2 && req.condaFile )
            throw new BadRequestException("Attribute `condaFile` is deprecated - use `packages` instead")
        if( v2 && req.spackFile )
            throw new BadRequestException("Attribute `spackFile` is deprecated - use `packages` instead")
        if( !v2 && req.packages )
            throw new BadRequestException("Attribute `packages` is not allowed")
        if( !v2 && req.nameStrategy )
            throw new BadRequestException("Attribute `nameStrategy` is not allowed by legacy container endpoint")

        // prevent the use of container file and freeze without a custom build repository
        if( req.containerFile && req.freeze && !isCustomRepo0(req.buildRepository) && (!v2 || (v2 && !req.packages)))
            throw new BadRequestException("Attribute `buildRepository` must be specified when using freeze mode [1]")

        // prevent the use of container image and freeze without a custom build repository
        if( req.containerImage && req.freeze && !isCustomRepo0(req.buildRepository) )
            throw new BadRequestException("Attribute `buildRepository` must be specified when using freeze mode [2]")

        if( v2 && req.packages && req.freeze && !isCustomRepo0(req.buildRepository) && !buildConfig.defaultPublicRepository )
            throw new BadRequestException("Attribute `buildRepository` must be specified when using freeze mode [3]")

        if( v2 && req.packages ) {
            // generate the container file required to assemble the container
            final generated = containerFileFromPackages(req.packages, req.formatSingularity())
            req = req.copyWith(containerFile: generated.bytes.encodeBase64().toString())
        }

        if( req.spackFile ) {
            throw new BadRequestException("Spack packages are not supported any more")
        }

        final ip = addressResolver.resolve(httpRequest)
        // check the rate limit before continuing
        if( rateLimiterService )
            rateLimiterService.acquirePull(new AcquireRequest(identity.userId as String, ip))
        // create request data
        final data = makeRequestData(req, identity, ip)
        final token = tokenService.computeToken(data)
        final target = targetImage(token.value, data.coordinates())
        final resp = v2
                        ? makeResponseV2(data, token, target)
                        : makeResponseV1(data, token, target)
        // persist request
        storeContainerRequest0(req, data, token, target, ip)
        // log the response
        log.debug "New container request fulfilled - token=$token.value; expiration=$token.expiration; container=$data.containerImage; build=$resp.buildId; identity=$identity"
        // return response
        return HttpResponse.ok(resp)
    }

    protected boolean isCustomRepo0(String repo) {
        if( !repo )
            return false
        if( buildConfig.defaultPublicRepository && repo.startsWith(buildConfig.defaultPublicRepository) )
            return false
        if( buildConfig.defaultBuildRepository && repo.startsWith(buildConfig.defaultBuildRepository) )
            return false
        if( buildConfig.defaultCacheRepository && repo.startsWith(buildConfig.defaultCacheRepository) )
            return false
        return true
    }

    protected void storeContainerRequest0(SubmitContainerTokenRequest req, ContainerRequestData data, TokenData token, String target, String ip) {
        try {
            final recrd = new WaveContainerRecord(req, data, target, ip, token.expiration)
            persistenceService.saveContainerRequest(token.value, recrd)
        }
        catch (Throwable e) {
            log.error("Unable to store container request with token: ${token}", e)
        }
    }

    protected String targetRepo(String repo, ImageNameStrategy strategy) {
        assert repo, 'Missing default public repository setting'
        // ignore everything that's not a public (community) repo
        if( !buildConfig.defaultPublicRepository )
            return repo
        if( !repo.startsWith(buildConfig.defaultPublicRepository) )
            return repo

        // check if the repository does use any reserved word
        final parts = repo.tokenize('/')
        if( parts.size()>1 && buildConfig.reservedWords ) {
            for( String it : parts[1..-1] ) {
                if( buildConfig.reservedWords.contains(it) )
                    throw new BadRequestException("Use of repository '$repo' is not allowed")
            }
        }

        // the repository is fully qualified use as it is
        if( parts.size()>1 ) {
            return repo
        }

        else {
            // consider strategy==null the same as 'imageSuffix' considering this is only going to be applied
            // to community registry which only allows build with Packages spec
            return repo + (strategy==null || strategy==ImageNameStrategy.imageSuffix ? '/library' : '/library/build')
        }
    }

    BuildRequest makeBuildRequest(SubmitContainerTokenRequest req, PlatformId identity, String ip) {
        if( !req.containerFile )
            throw new BadRequestException("Missing dockerfile content")
        if( !buildConfig.defaultBuildRepository )
            throw new BadRequestException("Missing build repository attribute")
        if( !buildConfig.defaultCacheRepository )
            throw new BadRequestException("Missing build cache repository attribute")

        final containerSpec = decodeBase64OrFail(req.containerFile, 'containerFile')
        final condaContent = condaFileFromRequest(req)
        final format = req.formatSingularity() ? SINGULARITY : DOCKER
        final platform = ContainerPlatform.of(req.containerPlatform)
        final buildRepository = targetRepo( req.buildRepository ?: (req.freeze && buildConfig.defaultPublicRepository
                ? buildConfig.defaultPublicRepository
                : buildConfig.defaultBuildRepository), req.nameStrategy)
        final cacheRepository = req.cacheRepository ?: buildConfig.defaultCacheRepository
        final configJson = inspectService.credentialsConfigJson(containerSpec, buildRepository, cacheRepository, identity)
        final containerConfig = req.freeze ? req.containerConfig : null
        final offset = DataTimeUtils.offsetId(req.timestamp)
        final scanMode = req.scanMode ?: ScanMode.async
        final scanId = scanEnabled && format==DOCKER && scanMode!=ScanMode.off ? LongRndKey.rndHex() : null
        // use 'imageSuffix' strategy by default for public repo images
        final nameStrategy = req.nameStrategy==null
                && buildRepository
                && buildConfig.defaultPublicRepository
                && buildRepository.startsWith(buildConfig.defaultPublicRepository) ? ImageNameStrategy.imageSuffix : req.nameStrategy

        checkContainerSpec(containerSpec)

        // create a unique digest to identify the build request
        final containerId = makeContainerId(containerSpec, condaContent, platform, buildRepository, req.buildContext)
        final targetImage = makeTargetImage(format, buildRepository, containerId, condaContent, nameStrategy)
        final maxDuration = buildConfig.buildMaxDuration(req)
        return new BuildRequest(
                containerId,
                containerSpec,
                condaContent,
                Path.of(buildConfig.buildWorkspace),
                targetImage,
                identity,
                platform,
                cacheRepository,
                ip,
                configJson,
                offset,
                containerConfig,
                scanId,
                req.buildContext,
                format,
                maxDuration,
                scanMode
        )
    }

    protected BuildTrack checkBuild(BuildRequest build, boolean dryRun) {
        final digest = registryProxyService.getImageDigest(build)
        // check for dry-run execution
        if( dryRun ) {
            log.debug "== Dry-run build request: $build"
            final dryId = build.containerId +  BuildRequest.SEP + '0'
            final cached = digest!=null
            return new BuildTrack(dryId, build.targetImage, cached)
        }
        // check for existing image
        if( digest ) {
            log.debug "== Found cached build for request: $build"
            final cache = persistenceService.loadBuild(build.targetImage, digest)
            return new BuildTrack(cache?.buildId, build.targetImage, true)
        }
        else {
            return buildService.buildImage(build)
        }
    }

    ContainerRequestData makeRequestData(SubmitContainerTokenRequest req, PlatformId identity, String ip) {
        if( !req.containerImage && !req.containerFile )
            throw new BadRequestException("Specify either 'containerImage' or 'containerFile' attribute")
        if( req.containerImage && req.containerFile )
            throw new BadRequestException("Attributes 'containerImage' and 'containerFile' cannot be used in the same request")
        if( req.containerImage?.contains('@sha256:') && req.containerConfig && !req.freeze )
            throw new BadRequestException("Container requests made using a SHA256 as tag does not support the 'containerConfig' attribute")
        if( req.formatSingularity() && !req.freeze )
            throw new BadRequestException("Singularity build is only allowed enabling freeze mode - see 'wave.freeze' setting")

        // expand inclusions
        inclusionService.addContainerInclusions(req, identity)

        // when 'freeze' is enabled, rewrite the request so that the container configuration specified
        // in the request is included in the build container file instead of being processed via the augmentation process
        if( req.freeze ) {
            req = freezeService.freezeBuildRequest(req, identity)
        }

        String targetImage
        String targetContent
        String condaContent
        String buildId
        boolean buildNew
        if( req.containerFile ) {
            final build = makeBuildRequest(req, identity, ip)
            final track = checkBuild(build, req.dryRun)
            targetImage = track.targetImage
            targetContent = build.containerFile
            condaContent = build.condaFile
            buildId = track.id
            buildNew = !track.cached
        }
        else if( req.mirrorRegistry ) {
            final mirror = makeMirrorRequest(req, identity)
            final track = checkMirror(mirror, identity, req.dryRun)
            targetImage = track.targetImage
            targetContent = null
            condaContent = null
            buildId = track.id
            buildNew = !track.cached
        }
        else if( req.containerImage ) {
            // normalize container image
            final coords = ContainerCoordinates.parse(req.containerImage)
            targetImage = coords.getTargetContainer()
            targetContent = null
            condaContent = null
            buildId = null
            buildNew = null
        }
        else
            throw new IllegalStateException("Specify either 'containerImage' or 'containerFile' attribute")

        new ContainerRequestData(
                identity,
                targetImage,
                targetContent,
                req.containerConfig,
                condaContent,
                ContainerPlatform.of(req.containerPlatform),
                buildId,
                buildNew,
                req.freeze,
                req.mirrorRegistry!=null
        )
    }

    protected MirrorRequest makeMirrorRequest(SubmitContainerTokenRequest request, PlatformId identity) {
        final coords = ContainerCoordinates.parse(request.containerImage)
        if( coords.registry == request.mirrorRegistry )
            throw new BadRequestException("Source and target mirror registry as the same - offending value '${request.mirrorRegistry}'")
        final targetImage = request.mirrorRegistry + '/' + coords.imageAndTag
        final configJson = inspectService.credentialsConfigJson(null, request.containerImage, targetImage, identity)
        final platform = request.containerPlatform
                ? ContainerPlatform.of(request.containerPlatform)
                : ContainerPlatform.DEFAULT
        final scanMode = request.scanMode ?: ScanMode.off
        final scanId = scanEnabled && scanMode!=ScanMode.off ? LongRndKey.rndHex() : null

        final digest = registryProxyService.getImageDigest(request.containerImage, identity)
        if( !digest )
            throw new BadRequestException("Container image '$request.containerImage' does not exist")
        return MirrorRequest.create(
                request.containerImage,
                targetImage,
                digest,
                platform,
                Path.of(buildConfig.buildWorkspace).toAbsolutePath(),
                configJson,
                scanId,
                scanMode
        )
    }

    protected BuildTrack checkMirror(MirrorRequest request, PlatformId identity, boolean dryRun) {
        final targetDigest = registryProxyService.getImageDigest(request.targetImage, identity)
        log.debug "== Mirror target digest: $targetDigest"
        final cached = request.digest==targetDigest
        // check for dry-run execution
        if( dryRun ) {
            log.debug "== Dry-run request request: $request"
            final dryId = request.mirrorId +  BuildRequest.SEP + '0'
            return new BuildTrack(dryId, request.targetImage, cached)
        }
        // check for existing image
        if( request.digest==targetDigest ) {
            log.debug "== Found cached request for request: $request"
            final cache = persistenceService.loadMirrorResult(request.targetImage, targetDigest)
            return new BuildTrack(cache?.mirrorId, request.targetImage, true)
        }
        else {
            return mirrorService.mirrorImage(request)
        }
    }

    protected String targetImage(String token, ContainerCoordinates container) {
        return "${new URL(serverUrl).getAuthority()}/wt/$token/${container.getImageAndTag()}"
    }

    @Get('/container-token/{token}')
    HttpResponse<DescribeWaveContainerResponse> describeContainerRequest(String token) {
        final data = persistenceService.loadContainerRequest(token)
        if( !data )
            throw new NotFoundException("Missing container record for token: $token")
        // return the response 
        return HttpResponse.ok( DescribeWaveContainerResponse.create(token, data) )
    }

    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Delete('/container-token/{token}')
    HttpResponse deleteContainerRequest(String token) {
        final record = tokenService.evictRequest(token)
        if( !record ){
            throw new NotFoundException("Missing container record for token: $token")
        }
        return HttpResponse.ok()
    }

    void validateContainerRequest(SubmitContainerTokenRequest req) throws BadRequestException {
        String msg
        // check valid image name
        msg = validationService.checkContainerName(req.containerImage)
        if( msg ) throw new BadRequestException(msg)
        // check build repo
        msg = validationService.checkBuildRepository(req.buildRepository, false)
        if( msg ) throw new BadRequestException(msg)
        // check cache repository
        msg = validationService.checkBuildRepository(req.cacheRepository, true)
        if( msg ) throw new BadRequestException(msg)
    }

    void validateMirrorRequest(SubmitContainerTokenRequest req, boolean v2) throws BadRequestException {
        if( !req.mirrorRegistry )
            return
        // container mirror validation
        if( !v2 )
            throw new BadRequestException("Container mirroring requires the use of v2 API")
        if( !req.containerImage )
            throw new BadRequestException("Attribute `containerImage` is required when specifying `mirrorRegistry`")
        if( !req.towerAccessToken )
            throw new BadRequestException("Container mirroring requires an authenticated request - specify the tower token attribute")
        if( req.freeze )
            throw new BadRequestException("Attribute `mirrorRegistry` and `freeze` conflict each other")
        if( req.containerFile )
            throw new BadRequestException("Attribute `mirrorRegistry` and `containerFile` conflict each other")
        if( req.containerIncludes )
            throw new BadRequestException("Attribute `mirrorRegistry` and `containerIncludes` conflict each other")
        if( req.containerConfig )
            throw new BadRequestException("Attribute `mirrorRegistry` and `containerConfig` conflict each other")
        final coords = ContainerCoordinates.parse(req.containerImage)
        if( coords.registry == req.mirrorRegistry )
            throw new BadRequestException("Source and target mirror registry as the same - offending value '${req.mirrorRegistry}'")
        def msg = validationService.checkMirrorRegistry(req.mirrorRegistry)
        if( msg )
            throw new BadRequestException(msg)
    }

    @Error(exception = AuthorizationException.class)
    HttpResponse<?> handleAuthorizationException() {
        return HttpResponse.unauthorized()
                .header(WWW_AUTHENTICATE, "Basic realm=Wave Authentication")
    }

    @Get('/v1alpha2/container/{containerId}')
    HttpResponse<WaveContainerRecord> getContainerDetails(String containerId) {
        final data = persistenceService.loadContainerRequest(containerId)
        if( !data )
            return HttpResponse.notFound()
        return HttpResponse.ok(data)
    }

}
