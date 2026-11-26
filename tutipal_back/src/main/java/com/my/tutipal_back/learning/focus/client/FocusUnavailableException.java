package com.my.tutipal_back.learning.focus.client;

/** PT-90 FastAPI focus prediction failed. The reason is a short code only (no input values). */
public class FocusUnavailableException extends RuntimeException {

    public FocusUnavailableException(String reason) {
        super(reason);
    }
}
