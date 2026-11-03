package com.auction.repository;

import com.auction.dto.State;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Server-only durable JSON snapshot, replaced atomically when supported. */
public final class FileStateRepository implements StateRepository {
    private final Path path;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public FileStateRepository(Path path) { this.path = path.toAbsolutePath(); }

    @Override public State load() throws IOException {
        if (!Files.exists(path)) return new State();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            State state = gson.fromJson(reader, State.class);
            if (state == null || state.users == null || state.auctions == null)
                throw new IOException("Invalid state file");
            return state;
        } catch (com.google.gson.JsonParseException e) {
            throw new IOException("Corrupted state file", e);
        }
    }

    @Override public void save(State state) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "auction-", ".tmp");
        try {
            Files.writeString(temp, gson.toJson(state), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }
}
