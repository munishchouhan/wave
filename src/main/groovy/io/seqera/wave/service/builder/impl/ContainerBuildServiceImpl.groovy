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

package io.seqera.wave.service.builder.impl

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.context.annotation.Requires
import io.micronaut.context.event.ApplicationEventPublisher
import io.micronaut.core.annotation.Nullable
import io.micronaut.scheduling.TaskExecutors
import io.seqera.wave.api.BuildContext
import io.seqera.wave.auth.RegistryCredentialsProvider
import io.seqera.wave.auth.RegistryLookupService
import io.seqera.wave.configuration.BuildConfig
import io.seqera.wave.configuration.BuildEnabled
import io.seqera.wave.configuration.HttpClientConfig
import io.seqera.wave.core.RegistryProxyService
import io.seqera.wave.exception.HttpServerRetryableErrorException
import io.seqera.wave.ratelimit.AcquireRequest
import io.seqera.wave.ratelimit.RateLimiterService
import io.seqera.wave.service.builder.BuildEntry
import io.seqera.wave.service.builder.BuildEvent
import io.seqera.wave.service.builder.BuildRequest
import io.seqera.wave.service.builder.BuildResult
import io.seqera.wave.service.builder.BuildStateStore
import io.seqera.wave.service.builder.BuildStrategy
import io.seqera.wave.service.builder.BuildTrack
import io.seqera.wave.service.builder.ContainerBuildService
import io.seqera.wave.service.job.JobHandler
import io.seqera.wave.service.job.JobService
import io.seqera.wave.service.job.JobSpec
import io.seqera.wave.service.job.JobState
import io.seqera.wave.service.metric.MetricsService
import io.seqera.wave.service.persistence.PersistenceService
import io.seqera.wave.service.persistence.WaveBuildRecord
import io.seqera.wave.service.scan.ContainerScanService
import io.seqera.wave.service.stream.StreamService
import io.seqera.wave.tower.PlatformId
import io.seqera.wave.util.RegHelper
import io.seqera.util.retry.Retryable
import io.seqera.wave.util.TarUtils
import jakarta.inject.Inject
import jakarta.inject.Named
import jakarta.inject.Singleton
import static io.seqera.wave.util.RegHelper.layerDir
import static io.seqera.wave.util.RegHelper.layerName
import static java.nio.file.StandardOpenOption.CREATE
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import static java.nio.file.StandardOpenOption.WRITE
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
/**
 * Implements container build service
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@Singleton
@Requires(bean = BuildEnabled)
@Named('Build')
@CompileStatic
class ContainerBuildServiceImpl implements ContainerBuildService, JobHandler<BuildEntry> {

    @Inject
    private BuildConfig buildConfig

    @Inject
    private ApplicationEventPublisher<BuildEvent> eventPublisher

    @Inject
    private BuildStateStore buildStore

    @Inject
    @Named(TaskExecutors.BLOCKING)
    private ExecutorService executor

    @Inject
    private RegistryLookupService lookupService

    @Inject
    private RegistryCredentialsProvider credentialsProvider

    @Inject
    private JobService jobService

    @Inject
    @Nullable
    private RateLimiterService rateLimiterService

    @Inject
    private HttpClientConfig httpClientConfig

    @Inject
    private StreamService streamService

    @Inject
    private PersistenceService persistenceService

    @Inject
    private MetricsService metricsService

    @Inject
    private RegistryProxyService proxyService

    @Inject
    @Nullable
    private ContainerScanService scanService

    @Inject
    private BuildStrategy buildStrategy

    /**
     * Build a container image for the given {@link io.seqera.wave.service.builder.BuildRequest}
     *
     * @param request
     *      A {@link io.seqera.wave.service.builder.BuildRequest} modelling the build request
     * @return
     *      The container image where the resulting image is going to be hosted
     */
    @Override
    BuildTrack buildImage(BuildRequest request) {
        return checkOrSubmit(request)
    }

    /**
     * Build a multi-platform container image for the given {@link MultiPlatformBuildRequest}
     *
     * @param request
     *      A {@link MultiPlatformBuildRequest} modelling the multi-platform build request
     * @return
     *      The build track for the multi-platform image
     */
    @Override
    BuildTrack buildMultiPlatformImage(MultiPlatformBuildRequest request) {
        if (request.singlePlatform) {
            // For single platform, delegate to the existing buildImage method
            return buildImage(request.baseRequest)
        }
        // For multi-platform, submit the multi-platform build
        return checkOrSubmitMultiPlatform(request)
    }

    /**
     * Check if multi-platform build exists or submit new one
     */
    protected BuildTrack checkOrSubmitMultiPlatform(MultiPlatformBuildRequest multiReq) {
        final req = multiReq.baseRequest
        final targetImage = req.targetImage
        
        // check if it's already been built
        final cached = buildStore.awaitBuild(targetImage)
        if( cached && cached.isDone() ) {
            log.debug "== Found multi-platform cached build for request: $req"
            final result = cached.join()
            final succeeded = result.exitStatus==0
            return new BuildTrack(req.buildId, targetImage, true, succeeded)
        }

        // rate limiting
        if( rateLimiter ) {
            final acquireRequest = new AcquireRequest(req.identity?.userEmail)
            if( !rateLimiter.acquireBuild(acquireRequest) ) {
                log.warn "Build request exceed plan quota limits - request: $req"
                throw new HttpServerRetryableErrorException("Build request exceed plan quota limits")
            }
        }

        // check for build already running
        final current = buildStore.getBuild(targetImage)
        if( current ) {
            log.debug "== Found existing multi-platform build for request: $req"
            return new BuildTrack(current.buildId, targetImage, false, null)
        }

        // submit it
        submitMultiPlatformBuild(multiReq)
        return new BuildTrack(req.buildId, targetImage, false, null)
    }

    /**
     * Submit a multi-platform build request
     */
    protected void submitMultiPlatformBuild(MultiPlatformBuildRequest multiReq) {
        final req = multiReq.baseRequest
        try {
            log.debug "== Submitting multi-platform build request: $req"
            buildStore.storeBuild(req.targetImage, new BuildEntry(req))
            // launch build execution
            executor.submit(() -> launchMultiPlatform(multiReq))
        }
        catch (Exception e) {
            log.error("Unexpected error submitting multi-platform build request: $req", e)
            buildStore.removeBuild(req.targetImage)
        }
    }

    /**
     * Launch the multi-platform build execution
     */
    protected void launchMultiPlatform(MultiPlatformBuildRequest multiReq) {
        try {
            final req = multiReq.baseRequest
            // create the workdir path
            Files.createDirectories(req.workDir)
            // create context dir
            final context = req.workDir.resolve('context')
            Files.createDirectories(context)

            // create docker container file
            final containerFile = containerFile0(req, context)
            final path = req.workDir.resolve('Containerfile')
            path.text = containerFile

            // create the docker config file
            createConfigFile(req)

            // create build context files
            createBuildContext(req.buildContext, context)

            // create conda recipe files
            createCondaFile(req, context)

            // launch the build
            log.info "== Launching multi-platform build: $req"
            final jobName = 'wx-' + req.buildId.substring(3)
            if (buildStrategy instanceof KubeBuildStrategy) {
                buildStrategy.buildMultiPlatform(jobName, multiReq)
            } else {
                // For other strategies, build each platform separately and create manifest list
                buildSeparatePlatforms(multiReq)
            }
        }
        catch (Exception e) {
            log.error("Unexpected error launching multi-platform build: ${multiReq.baseRequest}", e)
            buildStore.storeBuild(multiReq.baseRequest.targetImage, new BuildResult(BuildResult.Status.FAILED, Instant.now(), e.message, null))
        }
    }

    /**
     * Build separate platforms and create manifest list (fallback for non-Kubernetes strategies)
     */
    protected void buildSeparatePlatforms(MultiPlatformBuildRequest multiReq) {
        // This is a simplified approach - in a real implementation, you'd want to:
        // 1. Build each platform separately
        // 2. Create a manifest list that references all platform-specific images
        // For now, just build the first platform
        final firstPlatformReq = multiReq.createPlatformRequests()[0]
        launch(firstPlatformReq)
    }

    /**
     * Get a completable future that holds the build result
     *
     * @param targetImage
     *      the container repository name where the target image is expected to be retrieved once the
     *      build it complete
     * @return
     *      A completable future that holds the resulting {@link io.seqera.wave.service.builder.BuildResult} or
     *      {@code null} if not request has been submitted for such image
     */
    @Override
    CompletableFuture<BuildResult> buildResult(String targetImage) {
        return buildStore
                .awaitBuild(targetImage)
    }

    protected String containerFile0(BuildRequest req, Path context) {
        // add the context dir for singularity builds
        final containerFile = req.formatSingularity()
                ? req.containerFile.replace('{{wave_context_dir}}', context.toString())
                : req.containerFile

        return containerFile
    }

    protected void launch(BuildRequest req) {
        try {
            // create the workdir path
            Files.createDirectories(req.workDir)
            // create context dir
            final context = req.workDir.resolve('context')
            try { Files.createDirectory(context) }
            catch (FileAlreadyExistsException e) { /* ignore it */ }
            // save the dockerfile
            final containerFile = req.workDir.resolve('Containerfile')
            Files.write(containerFile, containerFile0(req, context).bytes, CREATE, WRITE, TRUNCATE_EXISTING)
            // save build context
            if( req.buildContext ) {
                saveBuildContext(req.buildContext, context, req.identity)
            }
            // save the conda file
            if( req.condaFile ) {
                final condaFile = context.resolve('conda.yml')
                Files.write(condaFile, req.condaFile.bytes, CREATE, WRITE, TRUNCATE_EXISTING)
            }
            // save docker config for creds
            Path configFile = null
            if( req.configJson ) {
                configFile = req.workDir.resolve('config.json')
                Files.write(configFile, JsonOutput.prettyPrint(req.configJson).bytes, CREATE, WRITE, TRUNCATE_EXISTING)
            }
            // save remote files for singularity
            if( configFile && req.formatSingularity()) {
                final remoteFile = req.workDir.resolve('singularity-remote.yaml')
                final content = RegHelper.singularityRemoteFile(req.targetImage)
                Files.write(remoteFile, content.bytes, CREATE, WRITE, TRUNCATE_EXISTING)
            }
            // save layers provided via the container config
            if( req.containerConfig ) {
                saveLayersToContext(req, context)
            }
            // launch the container build
            jobService.launchBuild(req)
        }
        catch (Throwable e) {
            log.error "== Container build unexpected exception: ${e.message} - request=$req", e
            final result = BuildResult.failed(req.buildId, e.message, req.startTime)
            handleBuildCompletion(new BuildEntry(req, result))
        }
    }

    protected void launchAsync(BuildRequest request) {
        // check the build rate limit
        try {
            if( rateLimiterService )
                rateLimiterService.acquireBuild(new AcquireRequest(request.identity.userEmail, request.ip))
        }
        catch (Exception e) {
            buildStore.removeBuild(request.targetImage)
            throw e
        }

        //increment metrics
        CompletableFuture
                .runAsync(() -> metricsService.incrementBuildsCounter(request.identity, request.platform.arch), executor)

        // launch the build async
        CompletableFuture
                .runAsync(() -> launch(request), executor)
    }

    protected BuildTrack checkOrSubmit(BuildRequest request) {
        // try to store a new build status for the given target image
        // this returns true if and only if such container image was not set yet
        final result = buildStore.putIfAbsentAndCount(request.targetImage, BuildEntry.create(request))
        if( result.succeed ) {
            // NOTE: when the entry is stored, the buildId is automatically incremented
            // therefore the request reference should be overridden
            request = result.value.request
            // go ahead with the launch
            log.info "== Container build submitted - request=$request"
            launchAsync(request)
            return new BuildTrack(request.buildId, request.targetImage, false, null)
        }
        // since it was unable to initialise the build result status
        // this means the build status already exists, retrieve it
        final ret2 = buildStore.getBuildResult(request.targetImage)
        if( ret2 ) {
            log.info "== Container build hit cache - request=$request"
            // note: mark as cached only if the build result is 'done'
            // if the build is still in progress it should be marked as not cached
            // so that the client will wait for the container completion
            return new BuildTrack(ret2.buildId, request.targetImage, ret2.done(), ret2.done() ? ret2.succeeded() : null)
        }
        // invalid state
        throw new IllegalStateException("Unable to determine build status for '$request.targetImage'")
    }

    protected void saveLayersToContext(BuildRequest req, Path contextDir) {
        if(req.formatDocker()) {
            saveLayersToDockerContext0(req, contextDir)
        }
        else if(req.formatSingularity()) {
            saveLayersToSingularityContext0(req, contextDir)
        }
        else
            throw new IllegalArgumentException("Unknown container format: $req.format")
    }

    protected void saveLayersToDockerContext0(BuildRequest request, Path contextDir) {
        final layers = request.containerConfig.layers
        for(int i=0; i<layers.size(); i++) {
            final it = layers[i]
            final target = contextDir.resolve(layerName(it))
            final retryable = retry0("Unable to copy '${it.location}' to docker context '${contextDir}'")
            // copy the layer to the build context
            retryable.apply(()-> {
                try (InputStream stream = streamService.stream(it.location, request.identity)) {
                    Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING)
                }
                return
            })
        }
    }

    protected void saveLayersToSingularityContext0(BuildRequest request, Path contextDir) {
        final layers = request.containerConfig.layers
        for(int i=0; i<layers.size(); i++) {
            final it = layers[i]
            final target = contextDir.resolve(layerDir(it))
            try { Files.createDirectory(target) }
            catch (FileAlreadyExistsException e) { /* ignore */ }
            // retry strategy
            final retryable = retry0("Unable to copy '${it.location} to singularity context '${contextDir}'")
            // copy the layer to the build context
            retryable.apply(()-> {
                try (InputStream stream = streamService.stream(it.location, request.identity)) {
                    TarUtils.untarGzip(stream, target)
                }
                return
            })
        }
    }

    protected void saveBuildContext(BuildContext buildContext, Path contextDir, PlatformId identity) {
        // retry strategy
        final retryable = retry0("Unable to copy '${buildContext.location} to build context '${contextDir}'")
        // copy the layer to the build context
        retryable.apply(()-> {
            try (InputStream stream = streamService.stream(buildContext.location, identity)) {
                TarUtils.untarGzip(stream, contextDir)
            }
            return
        })
    }

    private Retryable<Void> retry0(String message) {
        Retryable
                .<Void>of(httpClientConfig)
                .retryCondition((Throwable t) -> t instanceof SocketException || t instanceof HttpServerRetryableErrorException)
                .onRetry((event)-> log.warn("$message - event: $event"))
    }

    // **************************************************************
    // **               build job handle implementation
    // **************************************************************

    @Override
    BuildEntry getJobEntry(JobSpec job) {
        buildStore.getBuild(job.entryKey)
    }

    @Override
    void onJobCompletion(JobSpec job, BuildEntry entry, JobState state) {
        final buildId = entry.request.buildId
        final digest = state.succeeded()
                        ? proxyService.getImageDigest(entry.request, true)
                        : null
        // update build status store
        final result = state.completed()
                ? BuildResult.completed(buildId, state.exitCode, state.stdout, job.launchTime, digest)
                : BuildResult.failed(buildId, state.stdout, job.launchTime)
        handleBuildCompletion(entry.withResult(result))
        log.info "== Container build completed '${entry.request.targetImage}' - operation=${job.operationName}; exit=${state.exitCode}; status=${state.status}; duration=${result.duration}"
    }

    @Override
    void onJobException(JobSpec job, BuildEntry entry, Throwable error) {
        final result= BuildResult.failed(entry.request.buildId, error.message, job.launchTime ?: job.creationTime)
        handleBuildCompletion(entry.withResult(result))
        log.error("== Container build exception '${entry.request.targetImage}' - operation=${job.operationName}; cause=${error.message}", error)
    }

    @Override
    void onJobTimeout(JobSpec job, BuildEntry entry) {
        final buildId = entry.request.buildId
        final result= BuildResult.failed(buildId, "Container image build timed out '${entry.request.targetImage}'", job.launchTime)
        handleBuildCompletion(entry.withResult(result))
        log.warn "== Container build time out '${entry.request.targetImage}'; operation=${job.operationName}; duration=${result.duration}"
    }

    protected handleBuildCompletion(BuildEntry entry) {
        final event = new BuildEvent(entry.request, entry.result)
        final targetImage = entry.request.targetImage
        // since the underlying persistence is *not* transactional
        // the scan request should be submitted *before* updating the record
        // otherwise the scan status service can detect a complete build
        // for which a scan is requested but not scan record exists
        scanService?.scanOnBuild(entry)
        buildStore.storeBuild(targetImage, entry)
        persistenceService.saveBuildAsync(WaveBuildRecord.fromEvent(event))
        eventPublisher.publishEvent(event)
    }

    /**
     * {@inheritDoc}
     */
    @Override
    JobSpec launchJob(JobSpec job, BuildEntry entry) {
        buildStrategy.build(job.operationName, entry.request)
        // return the update job
        return job.withLaunchTime(Instant.now())
    }

    // **************************************************************
    // **               build record implementation
    // **************************************************************

    /**
     * Retrieve the build record for the specified id.
     *
     * @param buildId The ID of the build record to be retrieve
     * @return The {@link WaveBuildRecord} associated with the corresponding Id, or {@code null} if it cannot be found
     */
    @Override
    WaveBuildRecord getBuildRecord(String buildId) {
        final entry = buildStore.findByRequestId(buildId)
        return entry
                ? WaveBuildRecord.fromEntry(entry)
                : persistenceService.loadBuild(buildId)
    }

    /**
     * {@inheritDoc}
     */
    @Override
    WaveBuildRecord getLatestBuild(String containerId) {
        return persistenceService.latestBuild(containerId)
    }

    /**
     * {@inheritDoc}
     */
    @Override
    List<WaveBuildRecord> getAllBuilds(String containerId) {
        return persistenceService.allBuilds(containerId)
    }

}
