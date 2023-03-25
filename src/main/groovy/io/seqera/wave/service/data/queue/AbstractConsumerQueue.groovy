package io.seqera.wave.service.data.queue

import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.wave.encoder.EncodingStrategy
import io.seqera.wave.encoder.MoshiEncodeStrategy
import io.seqera.wave.exception.BadRequestException
import io.seqera.wave.util.TypeHelper
import jakarta.annotation.PreDestroy
import static io.seqera.wave.util.RegHelper.random256Hex

/**
 * Abstract consumer queue implementation that can use any available queue broker.
 *
 * @author Jordi Deu-Pons <jordi@seqera.io>
 * @param <V> Type of queue messages
 */
@Slf4j
@CompileStatic
abstract class AbstractConsumerQueue<V> implements ConsumerQueue<V>, ConsumerGroup<String>, AutoCloseable {

    private static final Random RANDOM = new Random()
    private Map<String, Map<String, Consumer<V>>> consumersMap = new ConcurrentHashMap<>()
    private QueueBroker<String> broker
    private EncodingStrategy<V> encodingStrategy

    AbstractConsumerQueue(QueueBroker<String> broker) {
        final type = TypeHelper.getGenericType(this, 0)
        this.encodingStrategy = new MoshiEncodeStrategy<V>(type) {}

        this.broker = broker
        this.broker.init(this)
    }

    @Override
    abstract String group()

    @Override
    synchronized void send(String queueKey, V request) {
        final message = encodingStrategy.encode(request)
        broker.send(queueKey, message)
    }

    @Override
    void consume(String queueKey, String message) {
        final consumers = consumersMap.get(queueKey).values() as List<Consumer<V>>
        if( consumers.size() <= 0) {
            log.warn "No available consumer listening at '$queueKey'"
            return
        }

        final consumer = consumers[RANDOM.nextInt(consumers.size())]
        final value = encodingStrategy.decode(message)
        consumer.accept(value)
    }

    @Override
    boolean canConsume(String queueKey) {
        return consumersMap.containsKey(queueKey) && consumersMap.get(queueKey).size() > 0
    }

    @Override
    synchronized String addConsumer(String queueKey, Consumer<V> consumer) {

        if( !consumersMap.containsKey(queueKey) )
            consumersMap.put(queueKey, new ConcurrentHashMap<String, Consumer<V>>())

        final consumerId = random256Hex()
        consumersMap.get(queueKey).put(consumerId, consumer)

        // Try to consume all pending queue messages
        // this can be the case if there is a disconnection just after a request is send
        // but not yet consumed
        broker.consume(queueKey, message -> {
            consumer.accept(encodingStrategy.decode(message))
        })

        return consumerId
    }

    @Override
    synchronized void removeConsumer(String queueKey, String consumerId) {

        if( !consumersMap.containsKey(queueKey) )
            throw new BadRequestException("No consumers at '${queueKey}'")

        final consumers = consumersMap.get(queueKey)
        if( !consumers.containsKey(consumerId) )
            throw new BadRequestException("No consumer with ID '${consumerId}'")

        consumers.remove(consumerId)
    }

    @PreDestroy
    @Override
    void close() {
        this.broker.close()
    }
}
