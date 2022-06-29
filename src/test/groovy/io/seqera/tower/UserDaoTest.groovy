package io.seqera.tower

import spock.lang.Shared
import spock.lang.Specification

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@MicronautTest
class UserDaoTest extends Specification{

    @Inject @Shared UserDao userDao

    def 'should access users' () {
        given:
        userDao.save(
                new User(id: 1, userName: 'me', email: 'me@google.com', deleted: false ) )
        and:
        userDao.save(
                new User(id: 2, userName: 'you', email: 'you@google.com', deleted: false ) )
        
        when:
        def users = userDao.findAll()
        then:
        users.size() == 2

        when:
        def u = userDao.findById(2).orElse(null)
        then:
        u.id == 2
        u.userName == 'you'
        u.email == 'you@google.com'
    }

}
