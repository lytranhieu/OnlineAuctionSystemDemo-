package com.auction.repository;

import com.auction.dto.State;
import java.io.IOException;

public interface StateRepository {
    State load() throws IOException;
    void save(State state) throws IOException;
}
