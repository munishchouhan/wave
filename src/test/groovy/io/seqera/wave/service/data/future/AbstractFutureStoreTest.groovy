package io.seqera.wave.service.data.future

import spock.lang.Specification

import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

import io.micronaut.context.annotation.Value
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.seqera.wave.encoder.MoshiEncodeStrategy
import jakarta.inject.Inject
import jakarta.inject.Singleton
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@MicronautTest
class AbstractFutureStoreTest extends Specification{

    @Singleton
    static class TestFutureStore extends AbstractFutureStore<String> {

        @Value('${wave.pairing.channel.timeout:1s}')
        Duration timeout

        TestFutureStore(FutureHash queue) {
            super(queue, new MoshiEncodeStrategy<String>() {})
        }

        @Override
        String prefix() {
            return 'foo:'
        }
    }

    @Inject
    TestFutureStore store

    def 'should offer and poll and value' () {

        when:
        def future = store.create('xyz')
        and:
        store.complete('xyz', 'hello')
        then:
        future.get() == 'hello'

    }

    def 'should timeout after one sec' () {
        when:
        def future = store.create('xyz')
        and:
        future.get()
        then:
        def err = thrown(ExecutionException)
        and:
        err.cause.class == TimeoutException
    }

}