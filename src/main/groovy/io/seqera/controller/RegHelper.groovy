package io.seqera.controller

import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.SecureRandom

import com.google.common.io.BaseEncoding
import groovy.json.JsonOutput
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class RegHelper {

    final private static char PADDING = '_' as char
    final private static MessageDigest SHA256 = java.security.MessageDigest.getInstance("SHA-256")
    final private static BaseEncoding BASE32 = BaseEncoding.base32() .withPadChar(PADDING)

    static private String bytesToHex(byte[] bytes) {
        StringBuffer result = new StringBuffer();
        for (byte byt : bytes) result.append(Integer.toString((byt & 0xff) + 0x100, 16).substring(1));
        return result.toString();
    }

    static String digest(String str) {
        return digest(str.getBytes())
    }

    static String digest(byte[] bytes) {
        final digest = SHA256.digest(bytes)
        return "sha256:${bytesToHex(digest)}"
    }

    static String encodeBase32(String str, boolean padding=true) {
        final result = BASE32.encode(str.bytes).toLowerCase()
        if( padding )
            return result
        final p = result.indexOf(PADDING as byte)
        return p == -1 ? result : result.substring(0,p)
    }

    static String decodeBase32(String encoded) {
        final result = BASE32.decode(encoded.toUpperCase())
        return new String(result)
    }

    static randomString(int len) {
        byte[] array = new byte[len]
        new Random().nextBytes(array)
        return new String(array, Charset.forName("UTF-8"));
    }

    static randomString(int min, int max) {
        Random random = new Random();
        final len = random.nextInt(max - min) + min
        randomString(len)
    }

    static String dumpJson(payload) {
        if( payload==null )
            return '(null)'
        try {
            return '\n' + JsonOutput.prettyPrint(payload.toString().trim())
        }
        catch( Throwable e ) {
            return '(no json output)'
        }
    }

    static String random256Hex() {
        final secureRandom = new SecureRandom();
        byte[] token = new byte[32]
        secureRandom.nextBytes(token)
        def result = new BigInteger(1, token).toString(16)
        // pad with extra zeros if necessary
        while( result.size()<64 )
            result = '0'+result
        return result
    }
}
