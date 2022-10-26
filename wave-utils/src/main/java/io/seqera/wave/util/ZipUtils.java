package io.seqera.wave.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.zip.DeflaterInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Helper class to basic compress/decompress functionality
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class ZipUtils {

    public static byte[] compress(InputStream stream) {
        try (stream) {
            return new DeflaterInputStream(stream).readAllBytes();
        }
        catch (IOException e) {
            throw new RuntimeException("Unable to compress provider stream", e);
        }
    }

    public static byte[] compress(byte[] bytes) {
        return compress(new ByteArrayInputStream(bytes));
    }

    public static byte[] compress(String text) throws IOException {
        return compress(new ByteArrayInputStream(text.getBytes()));
    }

    public static InputStream decompress(byte[] buffer) {
        return new InflaterInputStream(new ByteArrayInputStream(buffer));
    }

    public static String decompressAsString(byte[] buffer) throws IOException {
        return new String(decompress(buffer).readAllBytes());
    }

    public static byte[] decompressAsBytes(byte[] buffer) {
        try {
            return decompress(buffer).readAllBytes();
        }
        catch (IOException e) {
            throw new RuntimeException("Unable to decompress provider buffer", e);
        }
    }

    public static String encode(String data) throws IOException {
        if( data==null )
            return null;
        byte[] result = compress(data);
        return Base64.getEncoder().encodeToString(result);
    }

    public static String encode(InputStream data) {
        if( data==null )
            return null;
        byte[] result = compress(data);
        return Base64.getEncoder().encodeToString(result);
    }

    public static String decode(String encoded) throws IOException {
        if( encoded==null )
            return null;
        byte[] result = Base64.getDecoder().decode(encoded);
        return decompressAsString(result);
    }
}
