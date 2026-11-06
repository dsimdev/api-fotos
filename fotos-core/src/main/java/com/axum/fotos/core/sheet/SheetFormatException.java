package com.axum.fotos.core.sheet;

import java.io.IOException;

/**
 * The file cannot be used as a product list. The message is in Spanish on purpose: it is shown as is to the
 * person who dropped the file, who needs to know what to fix in it.
 */
public class SheetFormatException extends IOException {

    public SheetFormatException(String message) {
        super(message);
    }

    public SheetFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
