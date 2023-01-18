package io.seqera.wave.service.security

import java.security.KeyPair

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.tower.crypto.AsymmetricCipher
import io.seqera.wave.exchange.PairServiceResponse
import io.seqera.wave.util.DigestFunctions
import jakarta.inject.Inject

/**
 * https://www.baeldung.com/java-rsa
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class SecurityServiceImpl implements SecurityService {

    @Inject
    private KeysCacheStore store

    @Override
    PairServiceResponse getPublicKey(String service, String endpoint) {
        final uid =  makeKey(service,endpoint)

        def entry = store.get(uid)
        if (!entry) {
            log.debug "Pairing with service '${service}' at address $endpoint - key id: $uid"
            final keyPair = generate()
            final newEntry = new KeyRecord(service, endpoint, uid, keyPair.getPrivate().getEncoded(), keyPair.getPublic().getEncoded())
            // checking the presence of the entry before the if, only optimizes
            // the hot path, when the key has already been created, but cannot
            // guarantee the correctness in case of multiple concurrent invocations,
            // leading to the unfortunate case where the returned key does not correspond
            // to the stored one. Therefore we need an *atomic/transactional* putIfAbsent here
            entry = store.putIfAbsentAndGetCurrent(uid,newEntry)
        }
        else {
            log.trace "Paired already with service '${service}' at address $endpoint - using existing key id: $uid"
        }

        return new PairServiceResponse( keyId: uid, publicKey: entry.publicKey.encodeBase64() )
    }

    @Override
    KeyRecord getServiceRegistration(String service, String endpoint) {
        final uid = makeKey(service, endpoint)
        return store.get(uid)
    }

    protected static String makeKey(String service, String towerEndpoint) {
        final attrs = Map.<String,Object>of('service', service, 'towerEndpoint', towerEndpoint)
        return DigestFunctions.md5(attrs)
    }

    protected KeyPair generate() {
        final cipher = AsymmetricCipher.getInstance()
        return cipher.generateKeyPair()
    }
}
