package com.senko.droid;

import java.io.IOException;

/** the engine answered a request with ERR, or did not answer it */
final class ControlException extends IOException {
    private static final long serialVersionUID = 1L;

    ControlException(String message) {
        super(message);
    }

    ControlException(String message, Throwable cause) {
        super(message, cause);
    }
}
