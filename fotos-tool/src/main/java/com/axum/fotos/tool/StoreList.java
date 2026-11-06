package com.axum.fotos.tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The stores to ask, read from {@code stores.txt}: the list and its order are data, not code, because stores
 * change or leave and the order is a tie-break that gets re-tuned.
 */
final class StoreList {

    record Entry(String id, String baseUrl) {}

    private StoreList() {}

    static List<Entry> load() throws IOException {
        try (InputStream in = StoreList.class.getResourceAsStream("/stores.txt")) {
            if (in == null) throw new IOException("stores.txt is missing from the tool");
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
    }

    static List<Entry> parse(BufferedReader reader) throws IOException {
        var entries = new ArrayList<Entry>();
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length != 2) throw new IOException("stores.txt: expected '<id> <url>' but found: " + line);
            URI uri = URI.create(parts[1]);
            if (uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
                throw new IOException("stores.txt: not an http(s) address: " + parts[1]);
            }
            entries.add(new Entry(parts[0], parts[1].replaceAll("/+$", "")));
        }
        if (entries.isEmpty()) throw new IOException("stores.txt has no stores");
        return entries;
    }
}
