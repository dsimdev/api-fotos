package com.axum.fotos.stores;

/** An image could not be downloaded. The kind says whether trying another candidate makes sense. */
public class ImageDownloadException extends Exception {

    public enum Kind {
        /** The URL was refused before any request: not http(s), or it points at a private or local address. */
        REJECTED_URL,
        /** Network failure or timeout. */
        NETWORK,
        /** The server answered something other than an image with a 200. */
        HTTP_STATUS,
        /** Bigger than the cap: an image of a product is never that big. */
        TOO_LARGE,
        /** A 200 whose first bytes are not an image (an HTML error page, for instance). */
        NOT_AN_IMAGE
    }

    private final Kind kind;

    public ImageDownloadException(Kind kind, String message) {
        this(kind, message, null);
    }

    public ImageDownloadException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
