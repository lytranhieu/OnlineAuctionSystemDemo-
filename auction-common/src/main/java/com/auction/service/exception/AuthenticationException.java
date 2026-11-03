package com.auction.service.exception;

/** Invalid credentials for the connection-bound session. */
public final class AuthenticationException extends SecurityException {
    public AuthenticationException(String message) { super(message); }
}
