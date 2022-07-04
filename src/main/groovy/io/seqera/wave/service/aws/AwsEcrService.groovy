package io.seqera.wave.service.aws

import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.ecr.AmazonECR
import com.amazonaws.services.ecr.AmazonECRClientBuilder
import com.amazonaws.services.ecr.model.GetAuthorizationTokenRequest
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.google.common.util.concurrent.UncheckedExecutionException
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import jakarta.inject.Singleton
/**
 * Implement AWS ECR login service
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Singleton
@CompileStatic
class AwsEcrService {

    static final private Pattern AWS_ECR = ~/^(\d+)\.dkr\.ecr\.([a-z\-\d]+)\.amazonaws\.com/

    @Canonical
    private static class AwsCreds {
        String accessKey
        String secretKey
        String region
    }

    @Canonical
    static class AwsEcrHostInfo {
        String account
        String region
    }

    private CacheLoader<AwsCreds, String> loader = new CacheLoader<AwsCreds, String>() {
        @Override
        String load(AwsCreds creds) throws Exception {
            getLoginToken0(creds.accessKey, creds.secretKey, creds.region)
        }
    }

    private LoadingCache<AwsCreds, String> cache = CacheBuilder<URI, String>
            .newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(6, TimeUnit.HOURS)
            .build(loader)


    private AmazonECR ecrClient(String accessKey, String secretKey, String region) {
        AmazonECRClientBuilder
                .standard()
                .withRegion(region)
                .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))
                .build()
    }


    protected String getLoginToken0(String accessKey, String secretKey, String region) {
        final client = ecrClient(accessKey,secretKey,region)
        final resp = client.getAuthorizationToken(new GetAuthorizationTokenRequest())
        final encoded = resp.getAuthorizationData().get(0).getAuthorizationToken()
        return new String(encoded.decodeBase64())
    }

    /**
     * Get AWS ECR login token
     *
     * @param accessKey The AWS access key
     * @param secretKey The AWS secret key
     * @param region The AWS region
     * @return The ECR login token. The token is made up by the aws username and password separated by a `:`
     */
    String getLoginToken(String accessKey, String secretKey, String region) {
        assert accessKey, "Missing AWS accessKey argument"
        assert secretKey, "Missing AWS secretKet argument"
        assert region, "Missing AWS region argument"

        try {
            // get the token from the cache, if missing the it's automatically
            // fetch using the AWS ECR client
            return cache.get(new AwsCreds(accessKey,secretKey,region))
        }
        catch (UncheckedExecutionException e) {
            throw e.cause
        }
    }

    /**
     * Parse AWS ECR host name and return a pair made of account id and region code
     *
     * @param host
     *      The ECR host name e.g. {@code 195996028523.dkr.ecr.eu-west-1.amazonaws.com}
     * @return
     *      A pair holding the AWS account Id as first element and the AWS region as second element.
     *      If the value provided is not a valid ECR host name the {@code null} is returned
     */
    AwsEcrHostInfo getEcrHostInfo(String host) {
        if( !host )
            return null
        final m = AWS_ECR.matcher(host)
        if( !m.find() )
            return null
        return new AwsEcrHostInfo(m.group(1), m.group(2))
    }

    static boolean isEcrHost(String registry) {
        registry ? AWS_ECR.matcher(registry).find() : false
    }
}
