package com.groupdeal.dealservice.exception;

import org.springframework.http.HttpStatus;

public class DealPriceNotBelowOriginalPriceException extends DealServiceException {
    public DealPriceNotBelowOriginalPriceException() {
        super("dealPrice must be strictly less than the product's originalPrice");
    }
    @Override public HttpStatus httpStatus() { return HttpStatus.BAD_REQUEST; }
    @Override public String errorCode() { return "DEAL_PRICE_NOT_BELOW_ORIGINAL_PRICE"; }
}
