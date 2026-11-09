package com.auction.network;

import java.io.BufferedReader;
import java.io.IOException;

/** Read a bounded newline-delimited JSON message without allocating an unlimited line. */
public final class JsonLineFraming {
    private JsonLineFraming() { }

    /** Return null only on clean EOF before any input; CRLF and LF are accepted. */
    public static String readLine(BufferedReader reader, int maximumCharacters) throws IOException {
        if (maximumCharacters < 1) throw new IllegalArgumentException("maximumCharacters must be positive");
        StringBuilder content = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') break;
            if (content.length() >= maximumCharacters + 1L)
                throw new IOException("JSON message too long");
            content.append((char) c);
        }
        if (c == -1 && content.isEmpty()) return null;
        if (!content.isEmpty() && content.charAt(content.length() - 1) == '\r')
            content.setLength(content.length() - 1);
        if (content.length() > maximumCharacters)
            throw new IOException("JSON message too long");
        return content.toString();
    }
}
