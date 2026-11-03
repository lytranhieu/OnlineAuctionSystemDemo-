package com.auction.model;

/** A successfully accepted bid. Time is in Unix milliseconds. */
public record BidTransaction(String bidder, long amount, long at, boolean automatic) { }
