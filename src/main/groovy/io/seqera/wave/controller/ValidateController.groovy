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

package io.seqera.wave.controller

import javax.validation.Valid

import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.seqera.wave.auth.RegistryAuthService
import io.seqera.wave.model.ValidateRegistryCredsRequest
import jakarta.inject.Inject
import reactor.core.publisher.Mono

@Controller("/validate-creds")
class ValidateController {

    @Inject RegistryAuthService loginService

    @Post
    Mono<Boolean> validateCreds(@Valid ValidateRegistryCredsRequest request){
        Mono.just(
            loginService.validateUser(request.registry, request.userName, request.password)
        )
    }

}
