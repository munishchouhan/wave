package io.seqera.service
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
interface ContainerTokenService {

    /**
     * Get (generate) a new container token for the specified container request data
     *
     * @param request An instance of {@link ContainerRequestData}
     * @return A new token string that's used to track this request
     */
    String getToken(ContainerRequestData request)

    /**
     * Get the container image for the given container token
     *
     * @param token A container token string
     * @return the corresponding token string, or null if the token is unknown
     */
    ContainerRequestData getRequest(String token)

}
