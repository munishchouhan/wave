package io.seqera


import spock.lang.Specification
import spock.lang.Unroll
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class RouteHelperTest extends Specification {

    @Unroll
    def 'should match manifests route #PATH'() {

        when:
        def matcher = RouteHelper.ROUTE_PATHS.matcher(PATH)
        then:
        matcher.matches() == MATCHES
        and:
        MATCHES ? matcher.group(2) == TYPE : true
        MATCHES ? matcher.group(1) == NAME : true
        MATCHES ? matcher.group(3) == REFERENCE : true

        where:
        PATH                                        | MATCHES   | NAME                  | REFERENCE     | TYPE
        '/v2/hello-world/manifests/latest'          | true      | 'hello-world'         | 'latest'      | 'manifests'
        '/v2/library/hello-world/manifests/latest'  | true      | 'library/hello-world' | 'latest'      | 'manifests'
        '/v1/library/hello-world/manifests/latest'  | false     | null                  | null          | 'manifests'
        '/v2/library/hello-world/foo/latest'        | false     | null                  | null          | 'manifests'
        '/v2/foo:bar/manifests/latest'              | false     | null                  | null          | 'manifests'
        and:
        '/v2/hello-world/blobs/latest'              | true      | 'hello-world'         | 'latest'      | 'blobs'
        '/v2/library/hello-world/blobs/latest'      | true      | 'library/hello-world' | 'latest'      | 'blobs'
        '/v1/library/hello-world/blobs/latest'      | false     | null                  | null          | 'blobs'
        '/v2/library/hello-world/foo/latest'        | false     | null                  | null          | 'blobs'
        '/v2/foo:bar/manifests/latest'              | false     | null                  | null          | 'blobs'
        and:
        '/v2/hello-world/manifests/sha256'          | true      | 'hello-world'         | 'sha256'      | 'manifests'
        '/v2/hello-world/manifests/sha256:975f4b14f326b05db86e16de00144f9c12257553bba9484fed41f9b6f2257800' | true  | 'hello-world' | 'sha256:975f4b14f326b05db86e16de00144f9c12257553bba9484fed41f9b6f2257800' | 'manifests'
    }


    @Unroll
    def 'should get manifests route #PATH'() {
        when:
        def route = RouteHelper.parse(PATH)
        then:
        route == ROUTE

        where:
        PATH                                        | ROUTE
        '/v2/hello-world/manifests/latest'          | new RouteHelper.Route('manifests', 'hello-world', 'latest')
        '/v2/library/hello-world/manifests/latest'  | new RouteHelper.Route('manifests', 'library/hello-world', 'latest')
        '/v1/library/hello-world/manifests/latest'  | RouteHelper.NOT_FOUND
        '/v2/library/hello-world/foo/latest'        | RouteHelper.NOT_FOUND
        '/v2/foo:bar/blobs/latest'                  | RouteHelper.NOT_FOUND
        and:
        '/v2/hello-world/blobs/latest'              | new RouteHelper.Route('blobs', 'hello-world', 'latest')
        '/v2/library/hello-world/blobs/latest'      | new RouteHelper.Route('blobs', 'library/hello-world', 'latest')
        '/v1/library/hello-world/blobs/latest'      | RouteHelper.NOT_FOUND
        '/v2/library/hello-world/foo/latest'        | RouteHelper.NOT_FOUND
        '/v2/foo:bar/blobs/latest'                  | RouteHelper.NOT_FOUND
    }

    def 'should validate route type' () {

        when:
        def route = new RouteHelper.Route(TYPE, IMAGE, REF)
        then:
        route.isManifest() == IS_MANIFEST
        route.isBlob() == IS_BLOB
        route.isTag() == IS_TAG
        route.isDigest() == IS_DIGEST


        where:
        TYPE        | IMAGE | REF           | IS_MANIFEST   | IS_BLOB   | IS_TAG    | IS_DIGEST
        'manifests' | 'foo' | 'latest'      | true          | false     | true      | false
        'manifests' | 'foo' | 'sha256:1234' | true          | false     | false     | true
        and:
        'blobs'     | 'foo' | 'latest'      | false         | true      | true      | false
        'blobs'     | 'foo' | 'sha256:1234' | false         | true      | false     | true

    }

}