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
package io.seqera.wave.service.blob

import java.time.Duration
import java.time.Instant

import com.google.common.hash.Hashing
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import groovy.transform.ToString
import groovy.util.logging.Slf4j
/**
 * Model a blob cache metadata entry
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@ToString(includePackage = false, includeNames = true)
@Canonical
@CompileStatic
class BlobCacheInfo {

    /**
     * The HTTP location from the where the cached container blob can be retrieved
     */
    final String locationUri

    /**
     * The object storage path URI e.g. s3://bucket-name/some/path
     */
    final String objectUri

    /**
     * it is the name of k8s job or docker container depends on the transfer strategy
     */
    final String jobName

    /**
     * The request http headers
     */
    final Map<String,String> headers

    /**
     * The blob length
     */
    final Long contentLength

    /**
     * The content type of this blob
     */
    final String contentType

    /**
     * The blob cache control directive
     */
    final String cacheControl

    /**
     * The instant when the cache request was created
     */
    final Instant creationTime

    /**
     * The instant when the cache request completed
     */
    final Instant completionTime

    /**
     * The exit status of the command carried out to store the container
     * blob in the object storage cache
     */
    final Integer exitStatus

    /**
     * The output logs of the command carried out to store the container
     * blob in the object storage cache
     */
    final String logs

    String id() {
        return objectUri
    }

    boolean succeeded() {
        locationUri && exitStatus==0
    }

    boolean done() {
        locationUri && completionTime!=null
    }

    Duration duration() {
        creationTime && completionTime
                ? Duration.between(creationTime, completionTime)
                : null
    }

    static BlobCacheInfo create(String locationUri, String objectUri, Map<String,List<String>> request, Map<String,List<String>> response) {
        final headers0 = new LinkedHashMap<String,String>()
        for( Map.Entry<String,List<String>> it : request )
            headers0.put( it.key, it.value.join(',') )
        final length = headerLong0(response, 'Content-Length')
        final type = headerString0(response, 'Content-Type')
        final cache = headerString0(response, 'Cache-Control')
        final creationTime = Instant.now()
        return new BlobCacheInfo(locationUri, objectUri, generateJobName(locationUri, creationTime), headers0, length, type, cache, creationTime, null, null, null)
    }

    static String headerString0(Map<String,List<String>> headers, String name) {
        headers?.find(it-> it.key.toLowerCase()==name.toLowerCase())?.value?.first()
    }

    static Long headerLong0(Map<String,List<String>> headers, String name) {
        try {
            return headerString0(headers,name) as Long
        }
        catch (NumberFormatException e) {
            log.warn "Unexpected content length value - cause: $e"
            return null
        }
    }

    BlobCacheInfo cached() {
        new BlobCacheInfo(
                locationUri,
                objectUri,
                jobName,
                headers,
                contentLength,
                contentType,
                cacheControl,
                creationTime,
                creationTime,
                0,
                null
        )
    }

    BlobCacheInfo completed(int status, String logs) {
        new BlobCacheInfo(
                locationUri,
                objectUri,
                jobName,
                headers,
                contentLength,
                contentType,
                cacheControl,
                creationTime,
                Instant.now(),
                status,
                logs
        )
    }

    BlobCacheInfo failed(String logs) {
        new BlobCacheInfo(
                locationUri,
                objectUri,
                jobName,
                headers,
                contentLength,
                contentType,
                cacheControl,
                creationTime,
                Instant.now(),
                null,
                logs
        )
    }

    BlobCacheInfo withLocation(String location) {
        new BlobCacheInfo(
                location,
                objectUri,
                jobName,
                headers,
                contentLength,
                contentType,
                cacheControl,
                creationTime,
                completionTime,
                exitStatus,
                logs
        )
    }

    static BlobCacheInfo unknown(String logs) {
        new BlobCacheInfo(null, null, null, null, null, null, null, Instant.ofEpochMilli(0), Instant.ofEpochMilli(0), null, logs) {
            @Override
            BlobCacheInfo withLocation(String uri) {
                // prevent the change of location for unknown status
                return this
            }
        }
    }

    static private String generateJobName(String locationUri, Instant creationTime) {
        return 'transfer-' + Hashing
                .sipHash24()
                .newHasher()
                .putUnencodedChars(locationUri)
                .putUnencodedChars(creationTime.toString())
                .hash()
    }
}
