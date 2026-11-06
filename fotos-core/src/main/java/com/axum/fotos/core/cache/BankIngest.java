package com.axum.fotos.core.cache;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.image.ImageCodec;
import com.axum.fotos.core.image.ImageFormat;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Recognises the photos a person drops in the root of the bank: a file named {@code <EAN>.jpg} or {@code <EAN>.png}
 * becomes the photo of that EAN (see {@link LocalCache#saveManual}) and is taken out of the root.
 *
 * <p>The file is untrusted input, like everything that comes from outside: the NAME must be a valid EAN (and only that
 * validated value is ever used to build a path), the TYPE is decided by the first bytes and not by the extension, the
 * declared size is checked before decoding and a file that cannot be read as a JPEG or PNG is left where it is and
 * reported. Nothing is deleted unless it was saved first.
 */
public final class BankIngest {

    public record Skipped(String file, String reason) {}

    public record Report(List<Ean> ingested, List<Skipped> skipped) {}

    /** Bigger than this is not the photo of a product. */
    static final long MAX_BYTES = 15L * 1024 * 1024;

    /** The bank's own files and what Windows leaves around: never reported as "not an EAN". */
    private static final Set<String> NOT_PHOTOS = Set.of("indice.csv", "readme.md", ".gitignore", "desktop.ini", "thumbs.db");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif", "bmp", "tif", "tiff");

    private BankIngest() {}

    public static Report ingest(LocalCache bank, Clock clock) throws IOException {
        var ingested = new ArrayList<Ean>();
        var skipped = new ArrayList<Skipped>();
        if (!Files.isDirectory(bank.root())) return new Report(ingested, skipped);

        List<Path> dropped;
        try (var files = Files.list(bank.root())) {
            dropped = files.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : dropped) {
            String name = file.getFileName().toString();
            String lower = name.toLowerCase(Locale.ROOT);
            if (NOT_PHOTOS.contains(lower) || lower.startsWith(".") || lower.endsWith(".tmp")) continue;

            int dot = name.lastIndexOf('.');
            String extension = dot < 0 ? "" : lower.substring(dot + 1);
            if (!IMAGE_EXTENSIONS.contains(extension)) {
                skipped.add(new Skipped(name, "no es una imagen (tiene que ser .jpg o .png)"));
                continue;
            }
            Optional<Ean> ean = Ean.tryParse(name.substring(0, dot));
            if (ean.isEmpty()) {
                skipped.add(new Skipped(name, "el nombre tiene que ser el EAN y nada más (solo dígitos, con dígito verificador)"));
                continue;
            }
            Optional<String> problem = take(bank, ean.get(), file, clock);
            if (problem.isPresent()) skipped.add(new Skipped(name, problem.get()));
            else ingested.add(ean.get());
        }
        return new Report(ingested, skipped);
    }

    /** @return why the file was left alone, or empty when it was saved and taken out of the root */
    private static Optional<String> take(LocalCache bank, Ean ean, Path file, Clock clock) {
        try {
            if (Files.size(file) > MAX_BYTES) return Optional.of("es demasiado grande para ser la foto de un producto");
            byte[] bytes = Files.readAllBytes(file);
            ImageFormat format = ImageFormat.sniff(bytes);
            if (format == ImageFormat.WEBP) return Optional.of("es WebP, que no se puede leer: guardala como JPG o PNG");
            if (format == ImageFormat.UNKNOWN) return Optional.of("no es una imagen JPG ni PNG, aunque tenga esa extensión");
            BufferedImage image;
            try {
                image = ImageCodec.decode(bytes);
            } catch (IOException unreadable) {
                return Optional.of("no se pudo leer la imagen (¿está dañada o es enorme?)");
            }
            bank.saveManual(ean, bytes, format, Math.min(image.getWidth(), image.getHeight()), clock.instant());
        } catch (IOException e) {
            return Optional.of("no se pudo leer o guardar (¿se está copiando todavía?): volvé a correr");
        }
        try {
            Files.delete(file);
        } catch (IOException e) {
            // Saved already; the same file next time is the same photo and does not make a new "previa".
            return Optional.empty();
        }
        return Optional.empty();
    }
}
