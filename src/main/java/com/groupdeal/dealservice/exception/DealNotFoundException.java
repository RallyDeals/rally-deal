package com.groupdeal.dealservice.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class DealNotFoundException extends DealServiceException {
    public DealNotFoundException(UUID dealId) {
        super("No deal found with id " + dealId);
    }
    @Override public HttpStatus httpStatus() { return HttpStatus.NOT_FOUND; }
    @Override public String errorCode() { return "DEAL_NOT_FOUND"; }
}
