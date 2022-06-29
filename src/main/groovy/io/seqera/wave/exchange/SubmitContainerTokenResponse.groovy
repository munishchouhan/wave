package io.seqera.wave.exchange

import groovy.transform.CompileStatic

/**
 * Model a response for an augmented container
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
class SubmitContainerTokenResponse {
    String containerToken
    String targetImage
}
