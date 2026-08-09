package com.groupdeal.dealservice.exception;

import org.springframework.http.HttpStatus;

/**
 * Base for every business-rule rejection defined in design doc §5.1.
 * Each subclass fixes its HTTP status + error code; GlobalExceptionHandler
 * turns these into the standard { timestamp, status, error, message, path } body.
 */
public abstract class DealServiceException extends RuntimeException {

    protected DealServiceException(String message) {
        super(message);
    }

    public abstract HttpStatus httpStatus();

    public abstract String errorCode();
}
