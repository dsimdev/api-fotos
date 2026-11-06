package com.axum.fotos.core.sheet;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The first sheet of an .xlsx as rows of text, with no library: an .xlsx is a zip of XML files.
 *
 * <p>The file comes from a client, so it is not trusted: DTDs and external entities are switched off (an XML
 * file can otherwise make the parser read a local file or call out), and every entry is read through a size
 * cap (a small zip can inflate to gigabytes).
 *
 * <p>Cells are returned as the text a person would read: a number has no exponent and no ".0" (an EAN stored
 * as the number 7791337010604 must not become "7.791337010604E12"), and a text cell keeps its leading zeros.
 */
final class XlsxTable {

    private static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private XlsxTable() {}

    static List<List<String>> read(Path file) throws IOException {
        try (var zip = new ZipFile(file.toFile())) {
            String sheetPath = firstSheetPath(zip);
            List<String> shared = zip.getEntry("xl/sharedStrings.xml") == null ? List.of() : sharedStrings(zip);
            return sheet(zip, sheetPath, shared);
        } catch (XMLStreamException | java.util.zip.ZipException e) {
            throw new SheetFormatException("No pude leer la planilla: no parece un .xlsx válido.", e);
        }
    }

    private static String firstSheetPath(ZipFile zip) throws IOException, XMLStreamException {
        String relId = null;
        try (var in = open(zip, "xl/workbook.xml")) {
            var xml = reader(in);
            while (xml.hasNext() && relId == null) {
                if (xml.next() == XMLStreamConstants.START_ELEMENT && xml.getLocalName().equals("sheet")) {
                    relId = xml.getAttributeValue(REL_NS, "id");
                }
            }
        }
        if (relId == null) throw new SheetFormatException("La planilla no tiene hojas.");

        Map<String, String> targets = new HashMap<>();
        try (var in = open(zip, "xl/_rels/workbook.xml.rels")) {
            var xml = reader(in);
            while (xml.hasNext()) {
                if (xml.next() == XMLStreamConstants.START_ELEMENT && xml.getLocalName().equals("Relationship")) {
                    targets.put(xml.getAttributeValue(null, "Id"), xml.getAttributeValue(null, "Target"));
                }
            }
        }
        String target = targets.get(relId);
        if (target == null) throw new SheetFormatException("La planilla está dañada: no encuentro su primera hoja.");
        return target.startsWith("/") ? target.substring(1) : "xl/" + target;
    }

    private static List<String> sharedStrings(ZipFile zip) throws IOException, XMLStreamException {
        var strings = new ArrayList<String>();
        try (var in = open(zip, "xl/sharedStrings.xml")) {
            var xml = reader(in);
            StringBuilder current = null;
            int phonetic = 0;
            boolean inText = false;
            while (xml.hasNext()) {
                switch (xml.next()) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        switch (xml.getLocalName()) {
                            case "si" -> current = new StringBuilder();
                            case "rPh" -> phonetic++;
                            case "t" -> inText = true;
                            default -> { }
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (inText && phonetic == 0 && current != null) current.append(xml.getText());
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        switch (xml.getLocalName()) {
                            case "si" -> {
                                strings.add(current == null ? "" : current.toString());
                                current = null;
                            }
                            case "rPh" -> phonetic--;
                            case "t" -> inText = false;
                            default -> { }
                        }
                    }
                    default -> { }
                }
            }
        }
        return strings;
    }

    private static List<List<String>> sheet(ZipFile zip, String path, List<String> shared)
            throws IOException, XMLStreamException {
        var rows = new ArrayList<List<String>>();
        try (var in = open(zip, path)) {
            var xml = reader(in);
            TreeMap<Integer, String> row = null;
            String type = null;
            int column = 0;
            StringBuilder text = null;
            boolean inValue = false, inInline = false;
            while (xml.hasNext()) {
                switch (xml.next()) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        switch (xml.getLocalName()) {
                            case "row" -> row = new TreeMap<>();
                            case "c" -> {
                                type = xml.getAttributeValue(null, "t");
                                column = columnIndex(xml.getAttributeValue(null, "r"), row == null ? 0 : row.size());
                                text = new StringBuilder();
                            }
                            case "v" -> inValue = true;
                            case "is" -> inInline = true;
                            default -> { }
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if ((inValue || inInline) && text != null) text.append(xml.getText());
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        switch (xml.getLocalName()) {
                            case "v" -> inValue = false;
                            case "is" -> inInline = false;
                            case "c" -> {
                                if (row != null && text != null) row.put(column, value(type, text.toString(), shared));
                                text = null;
                            }
                            case "row" -> {
                                if (row != null) rows.add(dense(row));
                                row = null;
                                if (rows.size() > SpreadsheetReader.MAX_ROWS + 20) {
                                    throw new SheetFormatException("La planilla tiene demasiadas filas.");
                                }
                            }
                            default -> { }
                        }
                    }
                    default -> { }
                }
            }
        }
        return rows;
    }

    private static String value(String type, String raw, List<String> shared) {
        if (type == null || type.equals("n")) return plainNumber(raw.trim());
        return switch (type) {
            case "s" -> {
                try {
                    int index = Integer.parseInt(raw.trim());
                    yield index >= 0 && index < shared.size() ? shared.get(index) : "";
                } catch (NumberFormatException e) {
                    yield "";
                }
            }
            case "b" -> raw.trim().equals("1") ? "TRUE" : "FALSE";
            default -> raw;
        };
    }

    /** 7.791337010604E12 -> 7791337010604, 640008.0 -> 640008. Anything that is not a number is left as it is. */
    static String plainNumber(String raw) {
        if (raw.isEmpty()) return raw;
        try {
            var number = new BigDecimal(raw);
            return (number.scale() > 0 ? number.stripTrailingZeros() : number).toPlainString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /** "B4" -> 1, "AA10" -> 26. A cell without a reference follows the previous one. */
    private static int columnIndex(String reference, int fallback) {
        if (reference == null) return fallback;
        int index = 0, i = 0;
        while (i < reference.length() && Character.isLetter(reference.charAt(i))) {
            index = index * 26 + (Character.toUpperCase(reference.charAt(i)) - 'A' + 1);
            i++;
        }
        return i == 0 ? fallback : index - 1;
    }

    private static List<String> dense(TreeMap<Integer, String> row) {
        var cells = new ArrayList<String>();
        if (row.isEmpty()) return cells;
        for (int c = 0; c <= row.lastKey(); c++) cells.add(row.getOrDefault(c, ""));
        return cells;
    }

    private static InputStream open(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new SheetFormatException("La planilla está dañada: falta " + name + ".");
        return new CappedInputStream(zip.getInputStream(entry), MAX_ENTRY_BYTES);
    }

    private static XMLStreamReader reader(InputStream in) throws XMLStreamException {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        trySet(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        trySet(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.createXMLStreamReader(in);
    }

    private static void trySet(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // Not every StAX implementation knows these two; the three properties above already switch DTDs off.
        }
    }

    /** Fails instead of delivering more than {@code limit} bytes: the declared size of a zip entry can lie. */
    private static final class CappedInputStream extends FilterInputStream {
        private long left;

        CappedInputStream(InputStream in, long limit) {
            super(in);
            left = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && --left < 0) throw tooBig();
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0 && (left -= n) < 0) throw tooBig();
            return n;
        }

        private static IOException tooBig() {
            return new SheetFormatException("La planilla es demasiado grande para ser una lista de productos.");
        }
    }
}
