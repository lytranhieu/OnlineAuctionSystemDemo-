package com.auction.network;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

class JsonLineFramingTest {
    @Test void acceptsLfCrLfAndUnicodeWithoutChangingPacketBody() throws Exception {
        var reader = new BufferedReader(new StringReader("{\"message\":\"Đấu giá\"}\n{\"id\":1}\r\n"));
        assertEquals("{\"message\":\"Đấu giá\"}", JsonLineFraming.readLine(reader, 1024));
        assertEquals("{\"id\":1}", JsonLineFraming.readLine(reader, 1024));
        assertNull(JsonLineFraming.readLine(reader, 1024));
    }

    @Test void rejectsOversizedPacketBeforeAllocatingAnUnlimitedLine() {
        var reader = new BufferedReader(new StringReader("x".repeat(100) + "\n"));
        assertThrows(IOException.class, () -> JsonLineFraming.readLine(reader, 32));
    }

    @Test void readsFinalLineAtEof() throws Exception {
        var reader = new BufferedReader(new StringReader("{\"type\":\"PING\"}"));
        assertEquals("{\"type\":\"PING\"}", JsonLineFraming.readLine(reader, 64));
        assertNull(JsonLineFraming.readLine(reader, 64));
    }
}
