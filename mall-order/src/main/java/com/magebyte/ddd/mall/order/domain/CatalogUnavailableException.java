package com.magebyte.ddd.mall.order.domain;

public class CatalogUnavailableException extends RuntimeException {
    public CatalogUnavailableException(String message) {
        super(message);
    }

    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
