package com.groupdeal.dealservice.exception;

import org.springframework.http.HttpStatus;

public class ProductNotFoundException extends DealServiceException {
    public ProductNotFoundException(Long productId) {
        super("No product found with id " + productId + " in Catalog Service");
    }
    @Override public HttpStatus httpStatus() { return HttpStatus.NOT_FOUND; }
    @Override public String errorCode() { return "PRODUCT_NOT_FOUND"; }
}
