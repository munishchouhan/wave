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

import java.util.regex.Pattern

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.context.annotation.Value
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.QueryValue
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.views.View
import io.seqera.wave.exception.HttpResponseException
import io.seqera.wave.exception.NotFoundException
import io.seqera.wave.service.builder.ContainerBuildService
import io.seqera.wave.service.inspect.ContainerInspectService
import io.seqera.wave.service.logs.BuildLogService
import io.seqera.wave.service.mirror.ContainerMirrorService
import io.seqera.wave.service.mirror.MirrorResult
import io.seqera.wave.service.persistence.PersistenceService
import io.seqera.wave.service.persistence.WaveBuildRecord
import io.seqera.wave.service.persistence.WaveScanRecord
import io.seqera.wave.service.scan.ContainerScanService
import io.seqera.wave.service.scan.ScanEntry
import io.seqera.wave.util.JacksonHelper
import jakarta.inject.Inject
import static io.seqera.wave.util.DataTimeUtils.formatDuration
import static io.seqera.wave.util.DataTimeUtils.formatTimestamp
/**
 * Implements View controller
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
@Controller("/view")
@ExecuteOn(TaskExecutors.IO)
class ViewController {

    @Inject
    @Value('${wave.server.url}')
    private String serverUrl

    @Inject
    private PersistenceService persistenceService

    @Inject
    private ContainerBuildService buildService

    @Inject
    @Nullable
    private BuildLogService buildLogService

    @Inject
    private ContainerInspectService inspectService

    @Inject
    @Nullable
    private ContainerScanService scanService

    @Inject
    private ContainerMirrorService mirrorService

    @View("mirror-view")
    @Get('/mirrors/{mirrorId}')
    HttpResponse viewMirror(String mirrorId) {
        final result = mirrorService.getMirrorResult(mirrorId)
        if( !result )
            throw new NotFoundException("Unknown container mirror id '$mirrorId'")
        return HttpResponse.ok(renderMirrorView(result))
    }

    protected Map<String,Object> renderMirrorView(MirrorResult result) {
        // create template binding
        final binding = new HashMap(20)
        binding.mirror_id = result.mirrorId
        binding.mirror_success = result.succeeded()
        binding.mirror_failed = result.exitCode  && result.exitCode != 0
        binding.mirror_in_progress = result.exitCode == null
        binding.mirror_exitcode = result.exitCode ?: null
        binding.mirror_logs = result.exitCode ? result.logs : null
        binding.mirror_time = formatTimestamp(result.creationTime, result.offsetId) ?: '-'
        binding.mirror_duration = formatDuration(result.duration) ?: '-'
        binding.mirror_source_image = result.sourceImage
        binding.mirror_target_image = result.targetImage
        binding.mirror_platform = result.platform
        binding.mirror_digest = result.digest ?: '-'
        binding.mirror_user = result.userName ?: '-'
        binding.put('server_url', serverUrl)
        binding.scan_url = result.scanId && result.succeeded() ? "$serverUrl/view/scans/${result.scanId}" : null
        binding.scan_id = result.scanId
        return binding
    }

    @View("build-view")
    @Get('/builds/{buildId}')
    HttpResponse viewBuild(String buildId) {
        // check redirection for invalid suffix in the form `-nn`
        final r1 = shouldRedirect1(buildId)
        if( r1 ) {
            log.debug "Redirect to build page [1]: $r1"
            return HttpResponse.redirect(URI.create(r1))
        }
        // check redirection when missing the suffix `_nn`
        final r2 = shouldRedirect2(buildId)
        if( r2 ) {
            log.debug "Redirect to build page [2]: $r2"
            return HttpResponse.redirect(URI.create(r2))
        }
        // go ahead with proper handling
        final record = buildService.getBuildRecord(buildId)
        if( !record )
            throw new NotFoundException("Unknown container build id '$buildId'")
        return HttpResponse.ok(renderBuildView(record))
    }

    static final private Pattern DASH_SUFFIX = ~/([0-9a-zA-Z\-]+)-(\d+)$/

    static final private Pattern MISSING_SUFFIX = ~/([0-9a-zA-Z\-]+)(?<!_\d{2})$/

    protected String shouldRedirect1(String buildId) {
        // check for build id containing a -nn suffix
        final check1 = DASH_SUFFIX.matcher(buildId)
        if( check1.matches() ) {
            return "/view/builds/${check1.group(1)}_${check1.group(2)}"
        }
        return null
    }

    protected String shouldRedirect2(String buildId) {
        // check build id missing the _nn suffix
        if( !MISSING_SUFFIX.matcher(buildId).matches() )
            return null

        final rec = buildService.getLatestBuild(buildId)
        if( !rec )
            return null
        if( !rec.buildId.contains(buildId) )
            return null
        if( rec.buildId==buildId )
            return null

        return "/view/builds/${rec.buildId}"
    }

    Map<String,String> renderBuildView(WaveBuildRecord result) {
        // create template binding
        final binding = new HashMap(20)
        binding.build_id = result.buildId
        binding.build_success = result.succeeded()
        binding.build_failed = result.exitStatus  && result.exitStatus != 0
        binding.build_in_progress = result.exitStatus == null
        binding.build_exit_status = result.exitStatus
        binding.build_user = (result.userName ?: '-') + " (ip: ${result.requestIp})"
        binding.build_time = formatTimestamp(result.startTime, result.offsetId) ?: '-'
        binding.build_duration = formatDuration(result.duration) ?: '-'
        binding.build_image = result.targetImage
        binding.build_format = result.format?.render() ?: 'Docker'
        binding.build_platform = result.platform
        binding.build_containerfile = result.dockerFile ?: '-'
        binding.build_condafile = result.condaFile
        binding.build_digest = result.digest ?: '-'
        binding.put('server_url', serverUrl)
        binding.scan_url = result.scanId && result.succeeded() ? "$serverUrl/view/scans/${result.scanId}" : null
        binding.scan_id = result.scanId
        // configure build logs when available
        if( buildLogService ) {
            final buildLog = buildLogService.fetchLogString(result.buildId)
            binding.build_log_data = buildLog?.data
            binding.build_log_truncated = buildLog?.truncated
            binding.build_log_url = "$serverUrl/v1alpha1/builds/${result.buildId}/logs"
        }
        //add conda lock file when available
        if( buildLogService && result.condaFile ) {
            binding.build_conda_lock_data = buildLogService.fetchCondaLockString(result.buildId)
            binding.build_conda_lock_url = "$serverUrl/v1alpha1/builds/${result.buildId}/condalock"
        }
        // result the main object
        return binding
      }

    @View("container-view")
    @Get('/containers/{token}')
    HttpResponse<Map<String,Object>> viewContainer(String token) {
        final data = persistenceService.loadContainerRequest(token)
        if( !data )
            throw new NotFoundException("Unknown container token: $token")
        // return the response
        final binding = new HashMap(20)
        binding.request_token = token
        binding.request_container_image = data.containerImage
        binding.request_contaiener_platform = data.platform ?: '-'
        binding.request_fingerprint = data.fingerprint ?: '-'
        binding.request_timestamp = formatTimestamp(data.timestamp, data.zoneId) ?: '-'
        binding.request_expiration = formatTimestamp(data.expiration)
        binding.request_container_config = data.containerConfig

        binding.source_container_image = data.sourceImage ?: '-'
        binding.source_container_digest = data.sourceDigest ?: '-'

        binding.wave_container_show = !data.freeze && !data.mirror ? true : null
        binding.wave_container_image = data.waveImage ?: '-'
        binding.wave_container_digest = data.waveDigest ?: '-'

        // user & tower data
        binding.tower_user_id = data.user?.id
        binding.tower_user_email = data.user?.email
        binding.tower_user_name = data.user?.userName
        binding.tower_workspace_id = data.workspaceId ?: '-'
        binding.tower_endpoint = data.towerEndpoint

        binding.build_container_file = data.containerFile
        binding.build_conda_file = data.condaFile ?: '-'
        binding.build_repository = data.buildRepository ?: '-'
        binding.build_cache_repository = data.cacheRepository  ?: '-'
        binding.build_id = data.buildId ?: '-'
        binding.build_cached = data.buildId ? !data.buildNew : '-'
        binding.build_freeze = data.buildId ? data.freeze : '-'
        binding.build_url = data.buildId ? "$serverUrl/view/builds/${data.buildId}" : null
        binding.fusion_version = data.fusionVersion ?: '-'

        binding.scan_id = data.scanId
        binding.scan_url = data.scanId ? "$serverUrl/view/scans/${data.scanId}" : null

        binding.mirror_id = data.mirror ? data.buildId : null
        binding.mirror_url =  data.mirror ? "$serverUrl/view/mirrors/${data.buildId}" : null
        binding.mirror_cached = data.mirror ? !data.buildNew : null

        return HttpResponse.<Map<String,Object>>ok(binding)
    }

    @View("scan-view")
    @Get('/scans/{scanId}')
    HttpResponse<Map<String,Object>> viewScan(String scanId) {
        final binding = new HashMap(10)
        try {
            final result = loadScanRecord(scanId)
            log.debug "Render scan record=$result"
            makeScanViewBinding(result, binding)
        }
        catch (NotFoundException e){
            binding.scan_exist = false
            binding.scan_completed = true
            binding.error_message = e.getMessage()
            binding.should_refresh = false
        }

        // return the response
        binding.put('server_url', serverUrl)
        return HttpResponse.<Map<String,Object>>ok(binding)
    }

    /**
     * Retrieve a {@link ScanEntry} object for the specified build ID
     *
     * @param buildId The ID of the build for which load the scan result
     * @return The {@link ScanEntry} object associated with the specified build ID or throws the exception {@link NotFoundException} otherwise
     * @throws NotFoundException If the a record for the specified build ID cannot be found
     */
    protected WaveScanRecord loadScanRecord(String scanId) {
        if( !scanService )
            throw new HttpResponseException(HttpStatus.SERVICE_UNAVAILABLE, "Scan service is not enabled - Check Wave  configuration setting 'wave.scan.enabled'")
        final scanRecord = scanService.getScanRecord(scanId)
        if( !scanRecord )
            throw new NotFoundException("No scan report exists with id: ${scanId}")
        return scanRecord
    }

    @View("inspect-view")
    @Get('/inspect')
    HttpResponse<Map<String,Object>> viewInspect(@QueryValue String image, @Nullable @QueryValue String platform) {
        final binding = new HashMap(10)
        try {
            final spec = inspectService.containerSpec(image, platform, null)
            binding.imageName = spec.imageName
            binding.reference = spec.reference
            binding.digest = spec.digest
            binding.registry = spec.registry
            binding.hostName = spec.hostName
            binding.config = JacksonHelper.toJson(spec.config)
            binding.manifest = JacksonHelper.toJson(spec.manifest)
        }catch (Exception e){
            binding.error_message = e.getMessage()
        }

        // return the response
        binding.put('server_url', serverUrl)
        return HttpResponse.<Map<String,Object>>ok(binding)
    }

    Map<String, Object> makeScanViewBinding(WaveScanRecord result, Map<String,Object> binding=new HashMap(10)) {
        binding.should_refresh = !result.done()
        binding.scan_id = result.id
        binding.scan_container_image = result.containerImage ?: '-'
        binding.scan_exist = true
        binding.scan_completed = result.done()
        binding.scan_status = result.status
        binding.scan_failed = result.status == ScanEntry.FAILED
        binding.scan_succeeded = result.status == ScanEntry.SUCCEEDED
        binding.scan_exitcode = result.exitCode
        binding.scan_logs = result.logs
        // build info
        binding.build_id = result.buildId
        binding.build_url = result.buildId ? "$serverUrl/view/builds/${result.buildId}" : null
        // mirror info
        binding.mirror_id = result.mirrorId
        binding.mirror_url = result.mirrorId ? "$serverUrl/view/mirrors/${result.mirrorId}" : null
        // container info
        binding.request_id = result.requestId
        binding.request_url = result.requestId ? "$serverUrl/view/containers/${result.requestId}" : null

        binding.scan_time = formatTimestamp(result.startTime) ?: '-'
        binding.scan_duration = formatDuration(result.duration) ?: '-'
        if ( result.vulnerabilities )
            binding.vulnerabilities = result.vulnerabilities.toSorted().reverse()

        return binding
    }

}
