package com.groupdeal.dealservice.exception;

import org.springframework.http.HttpStatus;

public class DealAlreadyStartedException extends DealServiceException {
    public DealAlreadyStartedException() {
        super("Deal cannot be cancelled — at least one participant has already joined");
    }
    @Override public HttpStatus httpStatus() { return HttpStatus.CONFLICT; }
    @Override public String errorCode() { return "DEAL_ALREADY_STARTED"; }
}
