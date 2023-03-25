package io.seqera.wave.service.pairing.socket


import io.seqera.wave.service.data.future.AbstractFutureStore
import io.seqera.wave.service.data.future.FuturePublisher
import io.seqera.wave.service.pairing.socket.msg.PairingMessage
import jakarta.inject.Singleton
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Singleton
class PairingFutureStore extends AbstractFutureStore<PairingMessage> {

    PairingFutureStore(FuturePublisher<String> publisher) {
        super(publisher)
    }

    @Override
    String topic() {
        return "pairing-future-channel"
    }
}
