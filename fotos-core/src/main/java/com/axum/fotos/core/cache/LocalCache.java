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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * The photo bank on disk: one folder per EAN, named with the validated EAN (never with the SKU: the SKU is each
 * client's, the EAN is everybody's). No database: a few thousand products do not need one, and a folder can be looked
 * at, copied, backed up and served as plain files.
 *
 * <pre>
 *   &lt;EAN&gt;/entry.properties   where it came from, when, what kind
 *   &lt;EAN&gt;/image.jpg|png     the photo that is delivered (without the template panel, if it had one)
 *   &lt;EAN&gt;/original.jpg|png  the store's file as downloaded, BEFORE the crop (only when there was a crop)
 *   &lt;EAN&gt;/previa.jpg|png    the photo this one replaced, so a replacement never loses one
 * </pre>
 *
 * <p>Writes are idempotent (the natural key is the EAN) and atomic: the files go to temporary names first and the
 * entry is moved into place LAST, so an interrupted run leaves either the old entry or the new one, never half of one.
 */
public final class LocalCache {

    private static final String ENTRY = "entry.properties";
    private static final ImageFormat[] FORMATS = {ImageFormat.JPEG, ImageFormat.PNG};

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
            boolean ready = status == CacheEntry.Status.READY;
            ImageFormat format = ready ? ImageFormat.valueOf(p.getProperty("format", "")) : null;
            // Entries written before the bank had a source were all from a store.
            var source = CacheEntry.Source.valueOf(p.getProperty("source", CacheEntry.Source.STORE.name()));
            var entry = new CacheEntry(status, Instant.parse(p.getProperty("fetchedAt", "")), p.getProperty("store"),
                    p.getProperty("pageUrl"), p.getProperty("imageUrl"),
                    ready ? Candidate.Kind.valueOf(p.getProperty("kind", "")) : null, format,
                    Integer.parseInt(p.getProperty("side", "0")), Double.parseDouble(p.getProperty("edgeContact", "0")),
                    Double.parseDouble(p.getProperty("sharpness", "0")), p.getProperty("detail"), source,
                    ready && Files.isRegularFile(originalFile(ean, format)));
            if (ready && !Files.isRegularFile(imageFile(ean, format))) return Optional.empty();
            return Optional.of(entry);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Every EAN that has a folder in the bank, in order. Anything else in the root (the index, dropped photos) is not one. */
    public List<Ean> eans() throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (var entries = Files.list(root)) {
            return entries.filter(Files::isDirectory)
                    .map(dir -> Ean.tryParse(dir.getFileName().toString()))
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(Ean::value))
                    .toList();
        }
    }

    /** The photo of a READY entry. */
    public byte[] readImage(Ean ean, ImageFormat format) throws IOException {
        return Files.readAllBytes(imageFile(ean, format));
    }

    /** The store's file before the crop, of a READY entry that {@link CacheEntry#hasOriginal() has one}. */
    public byte[] readOriginal(Ean ean, ImageFormat format) throws IOException {
        return Files.readAllBytes(originalFile(ean, format));
    }

    public void saveReady(Ean ean, Candidate chosen, Instant now) throws IOException {
        var p = entry(CacheEntry.Status.READY, now);
        p.setProperty("store", chosen.store());
        put(p, "pageUrl", chosen.pageUrl());
        put(p, "imageUrl", chosen.imageUrl());
        p.setProperty("kind", chosen.kind().name());
        p.setProperty("format", chosen.format().name());
        p.setProperty("side", Integer.toString(chosen.side()));
        p.setProperty("edgeContact", Double.toString(chosen.edgeContact()));
        p.setProperty("sharpness", Double.toString(chosen.sharpness()));
        p.setProperty("source", CacheEntry.Source.STORE.name());
        write(ean, p, chosen.format(), chosen.bytes(), chosen.original());
    }

    /** A photo a person put in by hand. It is the photo of that EAN until another is put in; a refresh does not touch it. */
    public void saveManual(Ean ean, byte[] image, ImageFormat format, int side, Instant now) throws IOException {
        var p = entry(CacheEntry.Status.READY, now);
        p.setProperty("store", Candidate.OWN_STORE);
        p.setProperty("kind", Candidate.Kind.ORIGINAL.name());
        p.setProperty("format", format.name());
        p.setProperty("side", Integer.toString(side));
        p.setProperty("edgeContact", "0.0");
        p.setProperty("sharpness", "0.0");
        p.setProperty("source", CacheEntry.Source.MANUAL.name());
        write(ean, p, format, image, null);
    }

    public void saveEmpty(Ean ean, CacheEntry.Status status, String detail, Instant now) throws IOException {
        if (status == CacheEntry.Status.READY) throw new IllegalArgumentException("A READY entry needs a photo");
        Path folder = folder(ean);
        Files.createDirectories(folder);
        var p = entry(status, now);
        put(p, "detail", detail);
        Path entryTmp = writeEntryTmp(folder, p);
        // An empty answer over a photo must not lose the photo: it is kept as the previous one.
        for (ImageFormat f : FORMATS) {
            if (Files.isRegularFile(imageFile(ean, f))) copyAsPrevious(ean, imageFile(ean, f), f);
        }
        Files.move(entryTmp, folder.resolve(ENTRY), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        for (ImageFormat f : FORMATS) {
            Files.deleteIfExists(imageFile(ean, f));
            Files.deleteIfExists(originalFile(ean, f));
        }
    }

    /** Forgets an EAN completely, with its original and its previous photo. Nothing in a normal run calls this. */
    public void remove(Ean ean) throws IOException {
        Path folder = folder(ean);
        if (!Files.isDirectory(folder)) return;
        try (var files = Files.list(folder)) {
            for (Path f : files.toList()) Files.deleteIfExists(f);
        }
        Files.deleteIfExists(folder);
    }

    private void write(Ean ean, Properties entry, ImageFormat format, byte[] image, byte[] original) throws IOException {
        Path folder = folder(ean);
        Files.createDirectories(folder);
        Path imageTmp = folder.resolve("image.tmp");
        Files.write(imageTmp, image);
        Path originalTmp = null;
        if (original != null) {
            originalTmp = folder.resolve("original.tmp");
            Files.write(originalTmp, original);
        }
        Path entryTmp = writeEntryTmp(folder, entry);

        keepPrevious(ean, imageTmp);
        Files.move(imageTmp, imageFile(ean, format), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (originalTmp != null) {
            Files.move(originalTmp, originalFile(ean, format), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        Files.move(entryTmp, folder.resolve(ENTRY), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        // What is left of the previous entry and no longer matches this one: the other format, and an original
        // that belonged to a crop that is not the delivered photo any more.
        for (ImageFormat other : FORMATS) {
            if (other != format) {
                Files.deleteIfExists(imageFile(ean, other));
                Files.deleteIfExists(originalFile(ean, other));
            }
        }
        if (original == null) Files.deleteIfExists(originalFile(ean, format));
    }

    /** If a photo is about to be replaced by a DIFFERENT one, it is kept as the previous one (an identical one is not). */
    private void keepPrevious(Ean ean, Path replacement) throws IOException {
        for (ImageFormat f : FORMATS) {
            Path old = imageFile(ean, f);
            if (Files.isRegularFile(old) && Files.mismatch(old, replacement) != -1L) copyAsPrevious(ean, old, f);
        }
    }

    private void copyAsPrevious(Ean ean, Path old, ImageFormat format) throws IOException {
        for (ImageFormat f : FORMATS) Files.deleteIfExists(previousFile(ean, f));
        Files.copy(old, previousFile(ean, format), StandardCopyOption.REPLACE_EXISTING);
    }

    private static Properties entry(CacheEntry.Status status, Instant now) {
        var p = new Properties();
        p.setProperty("status", status.name());
        p.setProperty("fetchedAt", now.toString());
        return p;
    }

    private static void put(Properties p, String key, String value) {
        if (value != null) p.setProperty(key, value);
    }

    private static Path writeEntryTmp(Path folder, Properties entry) throws IOException {
        Path entryTmp = folder.resolve("entry.tmp");
        try (Writer out = Files.newBufferedWriter(entryTmp, StandardCharsets.UTF_8)) {
            entry.store(out, null);
        }
        return entryTmp;
    }

    private Path folder(Ean ean) {
        return root.resolve(ean.value());
    }

    private Path imageFile(Ean ean, ImageFormat format) {
        return folder(ean).resolve("image." + format.extension());
    }

    private Path originalFile(Ean ean, ImageFormat format) {
        return folder(ean).resolve("original." + format.extension());
    }

    private Path previousFile(Ean ean, ImageFormat format) {
        return folder(ean).resolve("previa." + format.extension());
    }
}
