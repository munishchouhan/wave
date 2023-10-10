/*
 *  Wave, containers provisioning service
 *  Copyright (c) 2023, Seqera Labs
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.seqera.wave.util

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

import groovy.transform.CompileStatic
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
class TimedInputStream extends FilterInputStream {

    private final InputStream target

    private final int timeoutMillis

    private volatile boolean closed

    TimedInputStream(InputStream inputStream, Duration timeout) {
        super(inputStream)
        this.target = inputStream
        this.timeoutMillis = (int)timeout.toMillis()
    }

    @Override
    int read() throws IOException {
        final result = CompletableFuture<Integer>.supplyAsync(() -> target.read())
        try {
            return result.get(timeoutMillis, TimeUnit.MILLISECONDS)
        }
        catch (Throwable t) {
            close()
            throw t
        }
    }

    @Override
    int read(byte[] b, int off, int len) throws IOException {
        final result = CompletableFuture<Integer>.supplyAsync(() -> target.read(b,off,len))
        try {
            return result.get(timeoutMillis, TimeUnit.MILLISECONDS)
        }
        catch (Throwable t) {
            close()
            throw t
        }
    }

    @Override
    void close() throws IOException {
        if( closed )
            return
        super.close()
        closed = true
    }
}
