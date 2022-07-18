package io.seqera.wave.storage


import java.util.concurrent.TimeUnit

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.wave.storage.reader.ContentReader
import jakarta.inject.Singleton
/**
 * @author : jorge <jorge.aguilera@seqera.io>
 * */
@Slf4j
@Singleton
@CompileStatic
class MemoryStorage implements Storage {

    private Cache<String, DigestStore> cache = CacheBuilder<String,DigestStore>
            .newBuilder()
            .maximumSize(1000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build()

    @Override
    Optional<DigestStore> getManifest(String path) {
        final result = cache.getIfPresent(path)
        result!=null ? Optional.of(result) : Optional.<DigestStore>empty()
    }

    @Override
    DigestStore saveManifest(String path, String manifest, String type, String digest) {
        log.debug "Save Manifest [size: ${manifest.size()}] ==> $path"
        final result = new ZippedDigestStore(manifest.getBytes(), type, digest);
        cache.put(path, result)
        return result;
    }

    @Override
    Optional<DigestStore> getBlob(String path) {
        final result = cache.getIfPresent(path)
        result!=null ? Optional.of(result) : Optional.<DigestStore>empty()
    }

    @Override
    DigestStore saveBlob(String path, byte[] content, String type, String digest) {
        log.debug "Save Blob [size: ${content.size()}] ==> $path"
        final result = new ZippedDigestStore(content, type, digest);
        cache.put(path, result)
        return result
    }

    @Override
    DigestStore saveBlob(String path, ContentReader content, String type, String digest) {
        log.debug "Save Blob ==> $path"
        final result = new LazyDigestStore(content, type, digest);
        cache.put(path, result)
        return result
    }
}