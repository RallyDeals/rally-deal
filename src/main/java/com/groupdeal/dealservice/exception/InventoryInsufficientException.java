package com.groupdeal.dealservice.exception;

import org.springframework.http.HttpStatus;

public class InventoryInsufficientException extends DealServiceException {
    public InventoryInsufficientException() {
        super("Inventory Service could not reserve the requested dealStock");
    }
    @Override public HttpStatus httpStatus() { return HttpStatus.CONFLICT; }
    @Override public String errorCode() { return "INVENTORY_INSUFFICIENT"; }
}
