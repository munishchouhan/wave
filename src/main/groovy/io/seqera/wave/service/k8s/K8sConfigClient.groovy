/*
 *  Copyright (c) 2023, Seqera Labs.
 *
 *  This Source Code Form is subject to the terms of the Mozilla Public
 *  License, v. 2.0. If a copy of the MPL was not distributed with this
 *  file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 *  This Source Code Form is "Incompatible With Secondary Licenses", as
 *  defined by the Mozilla Public License, v. 2.0.
 */

package io.seqera.wave.service.k8s

import javax.annotation.Nullable

import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.util.logging.Slf4j
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton

/**
 * Kubernetes client that connect to the cluster via kube
 * config in the local file system
 *
 * Check examples here
 * https://github.com/kubernetes-client/java/tree/master/examples/examples-release-13/src/main/java/io/kubernetes/client/examples
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
@Requires(property = 'wave.build.k8s.configPath')
@Singleton
class K8sConfigClient implements K8sClient {

    @Value('${wave.build.k8s.context}')
    @Nullable
    private String context

    @Value('${wave.build.k8s.configPath}')
    private String kubeConfigPath

    @Memoized
    ApiClient apiClient() {
        log.info "Creating K8s config with path: $kubeConfigPath -- context: '$context'"

        // load config
        final config = KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath))
        if( context ) {
            config.setContext(context)
        }

        // loading the out-of-cluster config, a kubeconfig from file-system
        return ClientBuilder
                .kubeconfig(config)
                .build()
    }

}
