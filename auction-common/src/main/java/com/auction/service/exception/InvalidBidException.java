package com.auction.service.exception;

/** A bid does not meet the auction's price rules. */
public final class InvalidBidException extends IllegalArgumentException {
    public InvalidBidException(String message) { super(message); }
}
