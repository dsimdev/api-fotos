package com.axum.fotos.tool;

import com.axum.fotos.stores.ImageDownloadException;
import com.axum.fotos.stores.SafeImageDownloader;

/** Downloads an image from a URL a store gave us. Behind an interface so the finder is tested without a network. */
@FunctionalInterface
public interface ImageFetcher {

    SafeImageDownloader.Downloaded fetch(String url) throws ImageDownloadException;
}
