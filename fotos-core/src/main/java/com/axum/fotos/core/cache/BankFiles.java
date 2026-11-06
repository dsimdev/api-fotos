package com.axum.fotos.core.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The two small files that make the bank folder explain itself and be safe to put under git: a README and a
 * {@code .gitignore} that versions the index and nothing else (the photos belong to the stores and brands, they do not
 * go to a repository). Written only when missing: a person's edits are never overwritten.
 */
public final class BankFiles {

    static final String README = """
            # Banco de fotos

            Una carpeta por **EAN** (el SKU no se guarda acá: es de cada cliente) con la foto que se entrega y lo necesario para rehacerla:

            - `image.jpg` o `image.png`: la foto que se entrega (ya sin el panel de la plantilla, si lo tenía).
            - `original.jpg` o `original.png`: la foto del comercio tal cual se bajó, **sin recortar** (solo si hubo recorte). Sirve para volver a recortar.
            - `previa.jpg` o `previa.png`: la foto a la que reemplazó la última vez que se cambió, para no perder ninguna.
            - `entry.properties`: de dónde salió (tienda, URL de la página y de la imagen), cuándo y de qué tipo es.

            `indice.csv` lista todo el banco y se regenera solo en cada corrida.

            ## Sumar una foto propia

            Dejá un archivo `<EAN>.jpg` o `<EAN>.png` **en esta carpeta** (el nombre es el EAN, nada más). La próxima corrida, o
            `fotos-tool --banco`, la reconoce, la guarda como la foto de ese EAN y la saca de acá. Una foto propia no se
            reemplaza con `--refresh`.

            ## Git

            Solo se versionan este README, el `.gitignore` y `indice.csv`. Las fotos no van a git.

            Si esta carpeta es un repo git, después de cada corrida (y de `fotos-tool --banco`) el programa commitea **solo**
            `indice.csv` cuando cambió y lo sube. Nunca fuerza nada ni baja cambios: si el remoto se movió por su cuenta, el
            push se rechaza y lo resolvés vos. Con `--sin-git` no toca git.
            """;

    static final String GITIGNORE = """
            # El banco guarda fotos de comercios y marcas: no van a git. Solo se versiona el indice.
            *
            !.gitignore
            !README.md
            !indice.csv
            """;

    private BankFiles() {}

    public static void ensure(Path bankRoot) throws IOException {
        Files.createDirectories(bankRoot);
        writeIfMissing(bankRoot.resolve("README.md"), README);
        writeIfMissing(bankRoot.resolve(".gitignore"), GITIGNORE);
    }

    private static void writeIfMissing(Path file, String content) throws IOException {
        if (!Files.exists(file)) Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
