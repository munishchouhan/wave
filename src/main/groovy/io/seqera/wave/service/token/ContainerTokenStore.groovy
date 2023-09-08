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

import io.seqera.wave.service.ContainerRequestData

/**
 * Define the container request token persistence operations
 * 
 * @author : jorge <jorge.aguilera@seqera.io>
 *
 */
interface ContainerTokenStore {

    void put(String key, ContainerRequestData request)

    ContainerRequestData get(String key)
}
