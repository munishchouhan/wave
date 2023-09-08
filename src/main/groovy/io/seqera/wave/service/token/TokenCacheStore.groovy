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

package io.seqera.wave.service.token

import java.time.Duration

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.wave.configuration.TokenConfig
import io.seqera.wave.encoder.MoshiEncodeStrategy
import io.seqera.wave.service.ContainerRequestData
import io.seqera.wave.service.cache.AbstractCacheStore
import io.seqera.wave.service.cache.impl.CacheProvider
import jakarta.inject.Singleton

/**
 * Implements a cache store for {@link ContainerRequestData}
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@Singleton
@CompileStatic
class TokenCacheStore extends AbstractCacheStore<ContainerRequestData> implements ContainerTokenStore {

    private TokenConfig tokenConfig

    TokenCacheStore(CacheProvider<String, String> delegate, TokenConfig tokenConfig) {
        super(delegate, new MoshiEncodeStrategy<ContainerRequestData>(){})
        this.tokenConfig = tokenConfig
        log.info "Creating Tokens cache store ― duration=${tokenConfig.cache.duration}"
    }

    @Override
    protected String getPrefix() {
        return 'wave-tokens/v1:'
    }

    @Override
    protected Duration getDuration() {
        return tokenConfig.cache.duration
    }

    @Override
    ContainerRequestData get(String key) {
        return super.get(key)
    }

    @Override
    void put(String key, ContainerRequestData value) {
        super.put(key, value)
    }
}
