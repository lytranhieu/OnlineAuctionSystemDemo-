package com.auction.dto;

import com.auction.model.Role;

/** Safe account data for administrators; never transmits password hashes. */
public record UserSummary(String username, Role role, boolean suspended) { }
