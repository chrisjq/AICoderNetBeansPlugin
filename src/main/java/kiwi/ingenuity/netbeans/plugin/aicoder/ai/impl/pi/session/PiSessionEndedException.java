package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.session;

import java.io.IOException;

/**
 * Fails every RPC still pending when the session ends underneath it (stream EOF or {@code close()}), as
 * opposed to pi answering or the write failing. The process exit path owns reporting that, so callers that
 * close busy work must not also close it on this failure.
 */
public final class PiSessionEndedException extends IOException {

    public PiSessionEndedException(String message) {
        super(message);
    }
}
