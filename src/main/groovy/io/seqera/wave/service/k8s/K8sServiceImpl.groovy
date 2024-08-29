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

package io.seqera.wave.service.k8s

import java.nio.file.Path
import java.time.Duration
import javax.annotation.PostConstruct

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.models.V1ContainerBuilder
import io.kubernetes.client.openapi.models.V1DeleteOptions
import io.kubernetes.client.openapi.models.V1EnvVar
import io.kubernetes.client.openapi.models.V1HostPathVolumeSource
import io.kubernetes.client.openapi.models.V1Job
import io.kubernetes.client.openapi.models.V1JobBuilder
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimVolumeSource
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodBuilder
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.openapi.models.V1ResourceRequirements
import io.kubernetes.client.openapi.models.V1Volume
import io.kubernetes.client.openapi.models.V1VolumeMount
import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Value
import io.micronaut.core.annotation.Nullable
import io.seqera.wave.configuration.BlobCacheConfig
import io.seqera.wave.configuration.BuildConfig
import io.seqera.wave.configuration.ScanConfig
import io.seqera.wave.core.ContainerPlatform
import io.seqera.wave.service.scan.Trivy
import jakarta.inject.Inject
import jakarta.inject.Singleton
import static io.seqera.wave.service.builder.BuildConstants.BUILDKIT_ENTRYPOINT
import static io.seqera.wave.service.builder.BuildConstants.FUSION_PREFIX
/**
 * implements the support for Kubernetes cluster
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@Singleton
@Requires(property = 'wave.build.k8s')
@CompileStatic
class K8sServiceImpl implements K8sService {

    @Value('${wave.build.k8s.namespace}')
    private String namespace

    @Value('${wave.build.k8s.debug:false}')
    private boolean debug

    @Value('${wave.build.k8s.storage.claimName}')
    @Nullable
    private String storageClaimName

    @Property(name='wave.build.k8s.labels')
    @Nullable
    private Map<String, String> labels

    @Property(name='wave.build.k8s.node-selector')
    @Nullable
    private Map<String, String> nodeSelectorMap

    @Value('${wave.build.k8s.service-account}')
    @Nullable
    private String serviceAccount

    @Value('${wave.build.k8s.resources.requests.cpu}')
    @Nullable
    private String requestsCpu

    @Value('${wave.build.k8s.resources.requests.memory}')
    @Nullable
    private String requestsMemory

    @Inject
    private K8sClient k8sClient

    @Inject
    private BuildConfig buildConfig

    // check this link to know more about these options https://github.com/moby/buildkit/tree/master/examples/kubernetes#kubernetes-manifests-for-buildkit
    private final static Map<String,String> BUILDKIT_FLAGS = ['BUILDKITD_FLAGS': '--oci-worker-no-process-sandbox']

    private Map<String, String> getBuildkitAnnotations(String containerName, boolean singularity) {
        if( singularity )
            return null
        final key = "container.apparmor.security.beta.kubernetes.io/${containerName}".toString()
        return Map.of(key, "unconfined")
    }

    /**
     * Validate config setting
     */
    @PostConstruct
    private void init() {
        log.info "K8s build config: namespace=$namespace; service-account=$serviceAccount; node-selector=$nodeSelectorMap; cpus=$requestsCpu; memory=$requestsMemory; buildWorkspaceBucket=$buildConfig.workspaceBucket;"
        if( !buildConfig.workspaceBucket )
            throw new IllegalArgumentException("Missing 'wave.build.workspaceBucket' configuration attribute")
        // validate node selectors
        final platforms = nodeSelectorMap ?: Collections.<String,String>emptyMap()
        for( Map.Entry<String,String> it : platforms ) {
            log.debug "Checking container platform '$it.key'; selector '$it.value'"
            ContainerPlatform.of(it.key) // <-- if invalid it will throw an exception
        }
    }

    /**
     * Create a K8s job with the specified name
     *
     * @param name
     *      The K8s job name. It must be unique
     * @param containerImage
     *      The container image to be used to run the job
     * @param args
     *      The command to be executed by the job
     * @return
     *      An instance of {@link V1Job}
     */
    @Override
    @CompileDynamic
    V1Job createJob(String name, String containerImage, List<String> args) {

        V1Job body = new V1JobBuilder()
                .withNewMetadata()
                    .withNamespace(namespace)
                    .withName(name)
                .endMetadata()
                .withNewSpec()
                    .withBackoffLimit(0)
                    .withNewTemplate()
                    .editOrNewSpec()
                    .addNewContainer()
                        .withName(name)
                        .withImage(containerImage)
                        .withArgs(args)
                    .endContainer()
                    .withRestartPolicy("Never")
                .endSpec()
                .endTemplate()
                .endSpec()
                .build()

        return k8sClient
                .batchV1Api()
                .createNamespacedJob(namespace, body, null, null, null,null)
    }

    /**
     * Get a Jobs Job.
     *
     * @param name The job name
     * @return An instance of {@link V1Job}
     */
    @Override
    V1Job getJob(String name) {
        k8sClient
                .batchV1Api()
                .readNamespacedJob(name, namespace, null)
    }

    /**
     * Get a Job status
     *
     * @param name The job name
     * @return  An instance of {@link JobStatus}
     */
    @Override
    JobStatus getJobStatus(String name) {
        final job = k8sClient
                .batchV1Api()
                .readNamespacedJob(name, namespace, null)
        if( !job )
            return null
        if( job.status.succeeded==1 )
            return JobStatus.Succeeded
        if( job.status.failed>0 )
            return JobStatus.Failed
        return JobStatus.Pending
    }

    /**
     * Get pod description
     *
     * @param name The pod name
     * @return An instance of {@link V1Pod} representing the job description
     */
    @Override
    V1Pod getPod(String name) {
        return k8sClient
                .coreV1Api()
                .readNamespacedPod(name, namespace, null)
    }

    /**
     * Create a container for container image building via buildkit
     *
     * @param name
     *      The name of pod
     * @param containerImage
     *      The container image to be used
     * @param args
     *      The build command to be performed
     * @param workDir
     *      The build context directory
     * @param creds
     *      The target container repository credentials
     * @return
     *      The {@link V1Pod} description the submitted pod
     */
    @Override
    V1Pod buildContainer(String name, String containerImage, List<String> args, String workDir, String creds, Duration timeout, Map<String,String> nodeSelector) {
        final spec = buildSpec(name, containerImage, args, workDir, creds, timeout, nodeSelector)
        return k8sClient
                .coreV1Api()
                .createNamespacedPod(namespace, spec, null, null, null,null)
    }

    V1Pod buildSpec(String name, String containerImage, List<String> args, String s3Key, String credsFile, Duration timeout, Map<String,String> nodeSelector) {

        // dirty dependency to avoid introducing another parameter
        final singularity = containerImage.contains('singularity')


        Map<String, String> env = new HashMap<String, String>()
        addAWSCreds(env)
        if( credsFile ){
            if( !singularity ) {
                env.put('DOCKER_CONFIG', "$FUSION_PREFIX/$buildConfig.workspaceBucket/$s3Key".toString())
            }
            else {
                env.put('DOCKER_CONFIG', "$FUSION_PREFIX/$buildConfig.workspaceBucket/$s3Key".toString())
                env.put('DOCKER_CONFIG', "$FUSION_PREFIX/$buildConfig.workspaceBucket/$s3Key".toString())
            }
        }

        V1PodBuilder builder = new V1PodBuilder()

        //metadata section
        builder.withNewMetadata()
                .withNamespace(namespace)
                .withName(name)
                .addToLabels(labels)
                .addToAnnotations(getBuildkitAnnotations(name,singularity))
                .endMetadata()

        //spec section
        def spec = builder
                .withNewSpec()
                .withNodeSelector(nodeSelector)
                .withServiceAccount(serviceAccount)
                .withActiveDeadlineSeconds( timeout.toSeconds() )
                .withRestartPolicy("Never")


        final requests = new V1ResourceRequirements()
        if( requestsCpu )
            requests.putRequestsItem('cpu', new Quantity(requestsCpu))
        if( requestsMemory )
            requests.putRequestsItem('memory', new Quantity(requestsMemory))

        //add https://github.com/nextflow-io/k8s-fuse-plugin
        requests.limits(Map.of("nextflow.io/fuse", new Quantity("1")))

        // container section
        final container = new V1ContainerBuilder()
                .withName(name)
                .withImage(containerImage)
                .withEnv(toEnvList(env))
                .withResources(requests)
                .withArgs(args)

        if( singularity ) {
            container.withNewSecurityContext().withPrivileged(true).endSecurityContext()
        }

        // spec section
        spec.withContainers(container.build()).endSpec()

        builder.build()
    }

    /**
     * Wait for a pod a completion.
     *
     * NOTE: this method assumes the pod is running exactly *one* container.
     *
     * @param pod
     *      The pod name
     * @param timeout
     *      Max wait time in milliseconds
     * @return
     *      An Integer value representing the container exit code or {@code null} if the state cannot be determined
     *      or timeout was reached.
     */
    @Override
    Integer waitPodCompletion(V1Pod pod, long timeout) {
        final start = System.currentTimeMillis()
        // wait for termination
        while( true ) {
            final phase = pod.status?.phase
            if(  phase && phase != 'Pending' ) {
                final status = pod.status.containerStatuses.first()
                if( !status )
                    return null
                if( !status.state )
                    return null
                if( status.state.terminated ) {
                    return status.state.terminated.exitCode
                }
            }

            if( phase == 'Failed' )
                return null
            final delta = System.currentTimeMillis()-start
            if( delta > timeout )
                return null
            sleep 5_000
            pod = getPod(pod.metadata.name)
        }
    }

    /**
     * Fetch the logs of a pod.
     *
     * NOTE: this method assume the pod runs exactly *one* container.
     *
     * @param name The {@link V1Pod} object representing the pod from where retrieve the logs
     * @return The logs as a string or when logs are not available or cannot be accessed
     */
    @Override
    String logsPod(V1Pod pod) {
        try {
            final logs = k8sClient.podLogs()
            logs.streamNamespacedPodLog(namespace, pod.metadata.name, pod.spec.containers.first().name).getText()
        }
        catch (Exception e) {
            log.error "Unable to fetch logs for pod: ${pod.metadata.name}", e
            return null
        }
    }

    /**
     * Delete a pod
     *
     * @param name The name of the pod to be deleted
     */
    @Override
    void deletePod(String name) {
        k8sClient
                .coreV1Api()
                .deleteNamespacedPod(name, namespace, (String)null, (String)null, (Integer)null, (Boolean)null, (String)null, (V1DeleteOptions)null)
    }

    /**
     * Delete a pod where the status is reached
     *
     * @param name The name of the pod to be deleted
     * @param statusName The status to be reached
     * @param timeout The max wait time in milliseconds
     */
    @Override
    void deletePodWhenReachStatus(String podName, String statusName, long timeout){
        final pod = getPod(podName)
        final start = System.currentTimeMillis()
        while( (System.currentTimeMillis() - start) < timeout ) {
            if( pod?.status?.phase == statusName ) {
                deletePod(podName)
                return
            }
            sleep 5_000
        }
    }

    @Override
    V1Pod scanContainer(String name, String containerImage, List<String> args, String s3Key, String creds, ScanConfig scanConfig, Map<String,String> nodeSelector) {
        final spec = scanSpec(name, containerImage, args, s3Key, creds, scanConfig, nodeSelector)
        return k8sClient
                .coreV1Api()
                .createNamespacedPod(namespace, spec, null, null, null,null)
    }

    V1Pod scanSpec(String name, String containerImage, List<String> args, String s3Key, String creds, ScanConfig scanConfig, Map<String,String> nodeSelector) {

        Map<String, String> env = new HashMap<String, String>()
        addAWSCreds(env)
        if( creds ){
            env.put('DOCKER_CONFIG', "$FUSION_PREFIX/$buildConfig.workspaceBucket/$s3Key".toString())
        }

        V1PodBuilder builder = new V1PodBuilder()

        //metadata section
        builder.withNewMetadata()
                .withNamespace(namespace)
                .withName(name)
                .addToLabels(labels)
                .endMetadata()

        //spec section
        def spec = builder
                .withNewSpec()
                .withNodeSelector(nodeSelector)
                .withServiceAccount(serviceAccount)
                .withActiveDeadlineSeconds( scanConfig.timeout.toSeconds() )
                .withRestartPolicy("Never")


        final requests = new V1ResourceRequirements()
        if( scanConfig.requestsCpu )
            requests.putRequestsItem('cpu', new Quantity(scanConfig.requestsCpu))
        if( scanConfig.requestsMemory )
            requests.putRequestsItem('memory', new Quantity(scanConfig.requestsMemory))

        //container section
        spec.addNewContainer()
                .withName(name)
                .withImage(containerImage)
                .withArgs(args)
                .withEnv(toEnvList(env))
                .withResources(requests)
                .endContainer()
                .endSpec()

        builder.build()
    }

    /**
     * Create a Job for blob transfer
     *
     * @param name
     *      The name of job and container
     * @param containerImage
     *      The container image to be used
     * @param args
     *      The transfer command to be performed
     * @param blobConfig
     *      The config to be used for transfer
     * @return
     *      The {@link V1Job} description the submitted job
     */
    @Override
    V1Job launchJob(String name, String containerImage, List<String> args, BlobCacheConfig blobConfig) {
        final spec = createTransferJobSpec(name, containerImage, args, blobConfig)

        return k8sClient
                .batchV1Api()
                .createNamespacedJob(namespace, spec, null, null, null,null)
    }

    V1Job createTransferJobSpec(String name, String containerImage, List<String> args, BlobCacheConfig blobConfig) {

        V1JobBuilder builder = new V1JobBuilder()

        //metadata section
        builder.withNewMetadata()
                .withNamespace(namespace)
                .withName(name)
                .withLabels(labels)
                .endMetadata()

        final requests = new V1ResourceRequirements()
        if( blobConfig.requestsCpu )
            requests.putRequestsItem('cpu', new Quantity(blobConfig.requestsCpu))
        if( blobConfig.requestsMemory )
            requests.putRequestsItem('memory', new Quantity(blobConfig.requestsMemory))

        //spec section
        def spec = builder.withNewSpec()
                .withBackoffLimit(blobConfig.retryAttempts)
                .withTtlSecondsAfterFinished(blobConfig.deleteAfterFinished.toSeconds() as Integer)
                .withNewTemplate()
                    .editOrNewSpec()
                    .withServiceAccount(serviceAccount)
                    .withActiveDeadlineSeconds(blobConfig.transferTimeout.toSeconds())
                    .withRestartPolicy("Never")
        //container section
                    .addNewContainer()
                        .withName(name)
                        .withImage(containerImage)
                        .withArgs(args)
                        .withResources(requests)
                        .withEnv(toEnvList(blobConfig.getEnvironment()))
                    .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()

        return spec.build()
    }

    protected List<V1EnvVar> toEnvList(Map<String,String> env) {
        final result = new ArrayList<V1EnvVar>(env.size())
        for( Map.Entry<String,String> it : env )
            result.add( new V1EnvVar().name(it.key).value(it.value) )
        return result
    }

    /**
     * Wait for a job to complete
     *
     * @param k8s job
     * @param timeout
     *      Max wait time in milliseconds
     * @return list of pods created by the job
     */
    @Override
    V1PodList waitJob(V1Job job, Long timeout) {
        sleep 5_000
        final startTime = System.currentTimeMillis()
        // wait for termination
        while (System.currentTimeMillis() - startTime < timeout) {
            final name = job.metadata.name
            final status = getJobStatus(name)
            if (status != JobStatus.Pending) {
                return k8sClient
                        .coreV1Api()
                        .listNamespacedPod(namespace, null, null, null, null, "job-name=$name", null, null, null, null, null, null)
            }
            job = getJob(name)
        }
        return null
    }

    /**
     * Delete a job
     *
     * @param name, name of the job to be deleted
     */
    @Override
    void deleteJob(String name) {
        k8sClient
                .batchV1Api()
                .deleteNamespacedJob(name, namespace, null, null, null, null,"Foreground", null)
    }

    @Override
    V1Pod getLatestPodForJob(String jobName) {
        // list all pods for the given job
        final allPods = k8sClient
                .coreV1Api()
                .listNamespacedPod(namespace, null, null, null, null, "job-name=${jobName}", null, null, null, null, null, null)

        if( !allPods )
            return null

        // Find the latest created pod among the pods associated with the job
        def latest = allPods.getItems().get(0)
        for (def pod : allPods.items) {
            if (pod.metadata?.creationTimestamp?.isAfter(latest.metadata.creationTimestamp)) {
                latest = pod
            }
        }
        return latest
    }

    private void addAWSCreds(Map <String, String> env) {
        env.put('AWS_ACCESS_KEY_ID', "${System.getenv('AWS_ACCESS_KEY_ID')}".toString())
        env.put('AWS_SECRET_ACCESS_KEY', "${System.getenv('AWS_SECRET_ACCESS_KEY')}".toString())
    }
}
