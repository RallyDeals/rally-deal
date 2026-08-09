package com.groupdeal.dealservice.exception;

import org.springframework.http.HttpStatus;

public class MinParticipantsExceedsStockException extends DealServiceException {
    public MinParticipantsExceedsStockException() {
        super("minParticipants must not exceed dealStock");
    }
    @Override public HttpStatus httpStatus() { return HttpStatus.BAD_REQUEST; }
    @Override public String errorCode() { return "MIN_PARTICIPANTS_EXCEEDS_STOCK"; }
}
