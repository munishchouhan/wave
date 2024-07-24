/*
 *  Wave, containers provisioning service
 *  Copyright (c) 2024, Seqera Labs
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

package io.seqera.wave.service.aws

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Requires
import io.micronaut.objectstorage.InputStreamMapper
import io.micronaut.objectstorage.ObjectStorageOperations
import io.micronaut.objectstorage.aws.AwsS3Configuration
import io.micronaut.objectstorage.aws.AwsS3Operations
import io.seqera.wave.configuration.BuildConfig
import jakarta.inject.Inject
import jakarta.inject.Named
import jakarta.inject.Singleton
import software.amazon.awssdk.services.s3.S3Client
/**
 * Factory implementation for ObjectStorageOperations
 *
 * @author Munish Chouhan <munish.chouhan@seqera.io>
 */
@Factory
@CompileStatic
@Slf4j
class ObjectStorageOperationsFactory {

    @Inject
    private BuildConfig buildConfig

    @Singleton
    @Named("build-logs")
    @Requires(property = 'wave.build.logs.bucket')
    ObjectStorageOperations<?, ?, ?> awsStorageOperationsBuildLogs(@Named("DefaultS3Client") S3Client s3Client, InputStreamMapper inputStreamMapper) {
        AwsS3Configuration configuration = new AwsS3Configuration('build-logs')
        configuration.setBucket(buildConfig.storageBucket)
        return new AwsS3Operations(configuration, s3Client, inputStreamMapper)
    }

    @Singleton
    @Named("build-workspace")
    @Requires(property = 'wave.build.workspace-bucket')
    ObjectStorageOperations<?, ?, ?> awsStorageOperationsBuildWorkspace(@Named("DefaultS3Client") S3Client s3Client, InputStreamMapper inputStreamMapper) {
        AwsS3Configuration configuration = new AwsS3Configuration('build-workspace')
        configuration.setBucket(buildConfig.workspaceBucket)
        return new AwsS3Operations(configuration, s3Client, inputStreamMapper)
    }
}
