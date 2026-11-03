package com.auction.model;

/** A proxy bidder sets a ceiling and a preferred step, at least the seller minimum. */
public record AutoBid(String bidder, long maxBid, long increment, long createdAt) { }
