package com.axum.fotos.app.config;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves the embedded admin panel (a Next static export) under /admin. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * How long the browser may cache each part of the panel. The two rules are opposite on purpose.
     *
     * <p>Without a {@code Cache-Control} the browser caches {@code index.html} with its heuristic,
     * and after an update whoever already had the panel open keeps seeing the previous one, whole
     * and correct: the old HTML points to old chunks that are cached too. With a login screen that
     * stops being annoying and becomes blocking: the screen changed and the backend expects another
     * thing. It happened to api-impuestos when its login moved to user plus password.
     *
     * <p>Chunks carry a hash in their name and never change: one year, immutable. {@code index.html}
     * has a fixed name and changes on every release: {@code no-cache}, which is not "do not store it"
     * but "store it and ask me before using it". With the ETag Spring already computes, the usual
     * answer is a 304 of a few bytes.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/admin/_next/static/**")
                .addResourceLocations("classpath:/static/admin/_next/static/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        registry.addResourceHandler("/admin/**")
                .addResourceLocations("classpath:/static/admin/")
                .setCacheControl(CacheControl.noCache());
    }

    /**
     * {@code /admin} has to resolve to the panel's index. Spring only does that for the site root,
     * not for a sub-directory.
     *
     * <p>A FORWARD, never {@code redirect:}. A redirect sends the browser a {@code Location: /admin/}
     * built with the path THIS process sees, not the one the browser sees: behind an IIS that hangs
     * the app under a sub-path the browser would end up at {@code /admin} on the domain ROOT, outside
     * the nested app. A forward emits no Location, so there is nothing for a proxy to rewrite. It is
     * the same bug that reached production three times in api-impuestos.
     *
     * <p>The panel HTML is public on purpose: it holds no credential. The password is typed by the
     * person using it and the token lives in their sessionStorage, never in the bundle.
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/admin").setViewName("forward:/admin/index.html");
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
    }
}
