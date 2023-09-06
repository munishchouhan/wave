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

package io.seqera.wave.tower.client

import groovy.transform.CompileStatic

/**
 * Models an encrypted credentials keys response
 *
 * @author Andrea Tortorella <andrea.tortorella@seqera.io>
 */
@CompileStatic
class GetCredentialsKeysResponse {

    /**
     * Secret keys associated with the credentials
     * The keys are encrypted using {@link io.seqera.tower.crypto.AsymmetricCipher}
     */
    String keys
}
