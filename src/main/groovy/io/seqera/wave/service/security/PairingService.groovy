package io.seqera.wave.service.security

import io.seqera.wave.exchange.PairServiceResponse
import io.seqera.wave.exchange.PairingResponse

/**
 * Provides public key generation for tower credentials integration.
 *
 * Once {@link PairingService#getPublicKey(java.lang.String, java.lang.String)} is
 * called a new {@link PairingRecord} for the requested service is generated and cached until it expires.
 *
 * Further invocation of {@link PairingService#getPublicKey(java.lang.String, java.lang.String)}
 * will not generate a new {@code KeyRecord} and return instead the public side of the already
 * generated one.
 *
 * Access to the currently generated {@code KeyRecord} for the corresponding service is provided
 * through {@link PairingService#getPairingRecord(java.lang.String, java.lang.String)}
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
interface PairingService {

    public static String TOWER_SERVICE = "tower"

    @Deprecated
    PairServiceResponse getPublicKey(String service, String endpoint)

    /**
     * Generates an return a key pair for the provided {@code service} available
     * at {@code endpoint}
     *
     * The key-pair is generated only if it is not already available for (service,endpoint)
     * otherwise the current key is returned.
     *
     * @param service The service name
     * @param endpoint The endpoint of the service
     * @return {@link PairingResponse} with the generated encoded public key
     */
    PairingResponse getPairingKey(String service, String endpoint)

    /**
     * Get the {@link PairingRecord} associated with {@code service} and {@code endpoint}
     * generated with {@link #getPublicKey(java.lang.String, java.lang.String)}
     *
     * @param service The service name
     * @param endpoint The endpoint of the service
     * @return {@link PairingRecord} if it has been generated and not expired, {@code null} otherwise
     */
    PairingRecord getPairingRecord(String service, String endpoint)
    
}