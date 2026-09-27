package com.sih.materialmaster.exception;

/** Raised when the external matching service cannot complete a requested operation. */
public class MatchingServiceException extends RuntimeException {
    public MatchingServiceException(String message) {
        super(message);
    }

    public MatchingServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
