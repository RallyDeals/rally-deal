package com.groupdeal.dealservice.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class ProductNotFoundException extends DealServiceException {
    public ProductNotFoundException(UUID productId) {
        super("No product found with id " + productId + " in Catalog Service");
    }
    @Override public HttpStatus httpStatus() { return HttpStatus.NOT_FOUND; }
    @Override public String errorCode() { return "PRODUCT_NOT_FOUND"; }
}
