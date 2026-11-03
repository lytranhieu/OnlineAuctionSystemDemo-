package com.auction.service.exception;

/** A bid was attempted before opening or after closing. */
public final class AuctionClosedException extends IllegalStateException {
    public AuctionClosedException(String message) { super(message); }
}
