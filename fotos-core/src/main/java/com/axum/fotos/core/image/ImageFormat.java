package com.axum.fotos.core.image;

/**
 * The real type of an image, decided by its first bytes and never by the Content-Type the server
 * claimed: one store answered {@code application/octet-stream} with a perfectly good image.
 */
public enum ImageFormat {
    JPEG("jpg"), PNG("png"), WEBP("webp"), UNKNOWN("");

    private final String extension;

    ImageFormat(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    public static ImageFormat sniff(byte[] head) {
        if (head.length >= 3 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0xd8 && (head[2] & 0xff) == 0xff) {
            return JPEG;
        }
        if (head.length >= 8 && (head[0] & 0xff) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0d && head[5] == 0x0a && head[6] == 0x1a && head[7] == 0x0a) {
            return PNG;
        }
        if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return WEBP;
        }
        return UNKNOWN;
    }
}
