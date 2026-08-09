package com.groupdeal.dealservice.exception;

import org.springframework.http.HttpStatus;

public class NotProductOwnerException extends DealServiceException {
    public NotProductOwnerException() {
        super("Caller does not own this product");
    }
    @Override public HttpStatus httpStatus() { return HttpStatus.FORBIDDEN; }
    @Override public String errorCode() { return "NOT_PRODUCT_OWNER"; }
}
