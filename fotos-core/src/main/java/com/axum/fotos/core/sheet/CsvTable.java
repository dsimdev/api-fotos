package com.axum.fotos.core.sheet;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A .csv as rows of text. Handles what Excel in Spanish actually produces: a semicolon as the separator, a
 * Windows-1252 encoding instead of UTF-8, a byte order mark, and quoted fields with commas or line breaks.
 */
final class CsvTable {

    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private CsvTable() {}

    static List<List<String>> read(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) throw new SheetFormatException("El archivo es demasiado grande para ser una lista de productos.");
        return parse(decode(Files.readAllBytes(file)));
    }

    /** UTF-8 when the bytes are valid UTF-8, Windows-1252 otherwise (what a Spanish-locale Excel writes). */
    static String decode(byte[] bytes) {
        int start = bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf ? 3 : 0;
        var body = ByteBuffer.wrap(bytes, start, bytes.length - start);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(body).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, start, bytes.length - start, WINDOWS_1252);
        }
    }

    static List<List<String>> parse(String text) {
        char delimiter = delimiterOf(text);
        var rows = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"' && field.isEmpty()) {
                quoted = true;
            } else if (c == delimiter) {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    /** The separator that appears most in the first line, outside quotes. */
    private static char delimiterOf(String text) {
        int comma = 0, semicolon = 0, tab = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (!quoted && (c == '\n' || c == '\r')) break;
            else if (!quoted) {
                if (c == ',') comma++;
                else if (c == ';') semicolon++;
                else if (c == '\t') tab++;
            }
        }
        if (semicolon >= comma && semicolon >= tab && semicolon > 0) return ';';
        if (tab > comma) return '\t';
        return ',';
    }
}
