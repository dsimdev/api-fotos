package com.axum.fotos.core.cache;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.image.ImageFormat;
import com.axum.fotos.core.pick.Candidate;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

/**
 * The cache on disk: one folder per EAN with {@code entry.properties} and, when there is a photo, {@code image.jpg}
 * or {@code image.png}. No database: a few thousand products do not need one, and a folder can be looked at,
 * copied and deleted by hand.
 *
 * <p>The folder name is the validated EAN, never text from the caller. Writes are idempotent (the natural key is
 * the EAN) and atomic: the image and the entry go to temporary files first and the entry is moved into place
 * LAST, so an interrupted run leaves either the old entry or the new one, never half of one.
 */
public final class LocalCache {

    private static final String ENTRY = "entry.properties";

    private final Path root;

    public LocalCache(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /** The entry, or empty if there is none or it is damaged (a damaged entry is looked up again, not trusted). */
    public Optional<CacheEntry> find(Ean ean) {
        Path file = folder(ean).resolve(ENTRY);
        if (!Files.isRegularFile(file)) return Optional.empty();
        var p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
            var status = CacheEntry.Status.valueOf(p.getProperty("status", ""));
            var entry = new CacheEntry(status, Instant.parse(p.getProperty("fetchedAt", "")), p.getProperty("store"),
                    p.getProperty("pageUrl"), p.getProperty("imageUrl"),
                    status == CacheEntry.Status.READY ? Candidate.Kind.valueOf(p.getProperty("kind", "")) : null,
                    status == CacheEntry.Status.READY ? ImageFormat.valueOf(p.getProperty("format", "")) : null,
                    Integer.parseInt(p.getProperty("side", "0")), Double.parseDouble(p.getProperty("edgeContact", "0")),
                    Double.parseDouble(p.getProperty("sharpness", "0")), p.getProperty("detail"));
            if (status == CacheEntry.Status.READY && !Files.isRegularFile(imageFile(ean, entry.format()))) return Optional.empty();
            return Optional.of(entry);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The photo of a READY entry. */
    public byte[] readImage(Ean ean, ImageFormat format) throws IOException {
        return Files.readAllBytes(imageFile(ean, format));
    }

    public void saveReady(Ean ean, Candidate chosen, Instant now) throws IOException {
        Path folder = folder(ean);
        Files.createDirectories(folder);
        Path image = imageFile(ean, chosen.format());
        Path imageTmp = folder.resolve("image.tmp");
        Files.write(imageTmp, chosen.bytes());

        var p = new Properties();
        p.setProperty("status", CacheEntry.Status.READY.name());
        p.setProperty("fetchedAt", now.toString());
        p.setProperty("store", chosen.store());
        put(p, "pageUrl", chosen.pageUrl());
        put(p, "imageUrl", chosen.imageUrl());
        p.setProperty("kind", chosen.kind().name());
        p.setProperty("format", chosen.format().name());
        p.setProperty("side", Integer.toString(chosen.side()));
        p.setProperty("edgeContact", Double.toString(chosen.edgeContact()));
        p.setProperty("sharpness", Double.toString(chosen.sharpness()));
        commit(folder, p, imageTmp, image);

        // The other format's file, if the previous entry had one, is now an orphan.
        for (ImageFormat other : new ImageFormat[]{ImageFormat.JPEG, ImageFormat.PNG}) {
            if (other != chosen.format()) Files.deleteIfExists(imageFile(ean, other));
        }
    }

    public void saveEmpty(Ean ean, CacheEntry.Status status, String detail, Instant now) throws IOException {
        if (status == CacheEntry.Status.READY) throw new IllegalArgumentException("A READY entry needs a photo");
        Path folder = folder(ean);
        Files.createDirectories(folder);
        var p = new Properties();
        p.setProperty("status", status.name());
        p.setProperty("fetchedAt", now.toString());
        put(p, "detail", detail);
        commit(folder, p, null, null);
        for (ImageFormat other : new ImageFormat[]{ImageFormat.JPEG, ImageFormat.PNG}) {
            Files.deleteIfExists(imageFile(ean, other));
        }
    }

    /** Forgets an EAN, so the next run looks it up again (the explicit refresh). */
    public void remove(Ean ean) throws IOException {
        Path folder = folder(ean);
        if (!Files.isDirectory(folder)) return;
        try (var files = Files.list(folder)) {
            for (Path f : files.toList()) Files.deleteIfExists(f);
        }
        Files.deleteIfExists(folder);
    }

    private static void put(Properties p, String key, String value) {
        if (value != null) p.setProperty(key, value);
    }

    private static void commit(Path folder, Properties entry, Path imageTmp, Path image) throws IOException {
        Path entryTmp = folder.resolve("entry.tmp");
        try (Writer out = Files.newBufferedWriter(entryTmp, StandardCharsets.UTF_8)) {
            entry.store(out, null);
        }
        if (imageTmp != null) Files.move(imageTmp, image, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.move(entryTmp, folder.resolve(ENTRY), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Path folder(Ean ean) {
        return root.resolve(ean.value());
    }

    private Path imageFile(Ean ean, ImageFormat format) {
        return folder(ean).resolve("image." + format.extension());
    }
}
