# Changelog

All notable changes to this project. Technical detail, in English — the audience is future-you
and whoever runs the next 360 audit. Write **why**, not just what.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) · Versioning:
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.4.0] - 2026-11-06

The cache becomes the photo bank: the photos are kept, by EAN, in a form that outlives VTEX and that a server can later
serve as plain files. The photos are for internal use (the store has them, nobody else is given the files).

### Added
- **The bank keeps what is needed to redo a photo.** Besides `image.*` (the photo that is delivered) a folder by EAN now holds
  `original.*` (the store's file as downloaded, BEFORE the crop, only when there was a crop, so a better cropper can redo it)
  and `previa.*` (the photo a replacement displaced). `entry.properties` says whether a store or a person provided the photo.
  Entries written by earlier versions still read fine (they count as from a store, without an original): the 111 cropped
  entries of the first catalog get their original the next time they are refreshed.
- **A photo a person drops in the bank is recognised.** A file `<EAN>.jpg` or `<EAN>.png` in the root of `banco-fotos` becomes the
  photo of that EAN at the start of every run, or with `fotos-tool --banco` (no sheet needed), and is taken out of the root.
  It is treated as outside input: the NAME must be a valid EAN and only that value ever builds a path, the type comes from the
  first bytes and not the extension, a declared canvas over 40 megapixels is refused before decoding, a file that cannot be
  read is left where it is and reported (nothing is deleted unless it was saved first), and WebP is refused with what to do.
  A photo put in by hand is never replaced by `--refresh` and no store is asked about it; the report says `propia`. Locked in by
  `BankIngestTest` (14), `PhotoFinderTest.aPhotoAPersonPutInIsUsedAsItIsAndNeverRefreshedNorAskedAboutAtAll` and `MainTest`.
- **`indice.csv`** in the root of the bank, one line per EAN in EAN order (state, origin, store, kind, size, whether it has an
  original, date, product page and image URL), rewritten after every run and byte-for-byte the same for the same bank, so it
  can be versioned. The bank has no SKU: it is the client's, not the bank's. A `README.md` and a `.gitignore` that versions only
  the index (the photos belong to the stores and brands) are created if missing and never overwritten.

### Changed
- **`--refresh` no longer deletes the stored photo before asking the stores.** It used to `remove` the entry first, so a refresh
  while a store was down, or after it stopped selling the product, lost the photo for good, which is exactly what a bank meant
  to outlive the stores cannot afford. Now a stored photo is replaced only when a new one is found (and the old one is kept as
  `previa`), and kept when every store fails, none sells it any more or only unusable images are left; the report says so.
  `PhotoFinderTest` (the four refresh cases, plus replace and keep-previous).
- The bank lives in `banco-fotos` INSIDE the program folder (`C:\Dev\app-fotos\banco-fotos`) instead of `%LOCALAPPDATA%`, and the
  installer never touches it. `--cache` and `FOTOS_CACHE` still override it. The console says "Banco de fotos".
- `LocalCache.saveEmpty` over a photo keeps the photo as `previa` instead of deleting it. The CSV cell writer (formula
  neutralising) is one class, `CsvCell`, shared by the report and the index.

## [0.3.0] - 2026-11-06

Everything since 0.2.0, driven by the first real use: where the deliveries go, the real Axum article export, a faster run,
and rows with bad data. The program is installed in `C:\Dev\app-fotos`.

### Added
- `--paralelo <1-8>` (default 3): several products are looked up at the same time. One at a time left the program waiting for
  the slowest of the seven stores on every product. Measured on the real 130-product sheet: 127 s against 238 s, with the
  same pick on all 130 rows, no store warning, and each store still paced at 250 ms between requests. Lines come back in the
  order of the rows; an EAN repeated across rows is looked up once even when those rows run together (a future per EAN); the
  progress line is printed by one thread at a time. Decoding and analysing photos is capped by a semaphore (cores, at most 6)
  so memory stays modest however many downloads are in flight. `BatchRunParallelTest` (really concurrent, never more than
  asked, order, single lookup, progress, a failing row, same result as one by one), repeated six times to rule out flakiness.

### Changed
- The delivery folder is now `Fotos <date>` (e.g. `Fotos 2026-11-06`) created INSIDE the folder the program is installed
  in, no longer next to the dropped sheet: the sheet usually comes from Downloads and that is not where deliveries should
  pile up. A new `--salida <folder>` option puts it elsewhere; run from a build rather than an install it still writes
  next to the sheet. A second run the same day is `Fotos <date> (2)`, never a reuse. `DeliveryWriter.newFolderNextTo`
  became `newFolder(root, name)`; the name is chosen by the caller, not taken from the client sheet. Locked in by
  `MainTest` and `DeliveryWriterTest.theDeliveryFolderIsNeverReused`.
- `scripts/herramienta/instalar.ps1` installs to `C:\Dev\app-fotos` and no longer creates a desktop shortcut (each person
  makes theirs). Because deliveries now live in the install folder, an update replaces only `bin`, `lib` and the launcher
  and never wipes the folder; it still refuses a non-empty folder that is not a previous install.
- Delivery folders are git-ignored by their new name (`Fotos 20??-??-??` and its `(n)` copies), which is what a run writes next to a
  sheet when it is not running from an install. The old `* - fotos` rules matched a name nothing produces any more.
- A row with no barcode now says "No tiene código de barras (EAN) en la planilla" instead of "EAN inválido": in an export of
  a client whose articles have no barcode loaded, that is the common case and "invalid" sent people looking for a typo.
  `BatchRunTest.aRowWithoutAnEanSaysItIsMissingInsteadOfInvalid`.
- **Rows with bad data are ignored** (no EAN, invalid EAN, no SKU, a SKU that is not a file name, a SKU already used by another
  EAN): no store is asked about them, they are not printed one by one, and they have no card in the gallery. They are counted
  by kind in the summary and explained in `reporte.csv`. With a client export of thousands of articles, most of them without
  a barcode, the screen would otherwise print thousands of lines that bury the ones that matter. The rules moved to one place,
  `RowChecker`, which runs BEFORE any lookup: a SKU that cannot be a file name used to be looked up and only discarded when the
  delivery was written, spending store requests on something never delivered. The progress total is now the number of rows
  actually looked up. `DeliveryLine` gained `problem` so the summary can count by kind; `DeliveryWriter` keeps its own check as
  a last line of defence for lines built by hand. Locked in by `RowCheckerTest`, `BatchRunTest` (never asked, progress only for
  looked-up rows, everything ignored), `DeliveryWriterTest.anIgnoredRowHasNoCardInTheGalleryButStaysInTheReport` and
  `MainTest.manyRowsWithBadDataArePrintedAsOneBlockOfCountsNotOneLineEach` (340 bad rows: one progress line).

### Fixed
- Axum's article export was rejected ("no encontré las columnas SKU y EAN"): it names the barcode column `codbarra`, singular,
  and only `codbarras` was recognised. The singular forms were added; `codigo` was already the SKU.
  `SpreadsheetReaderTest.readsTheArticleExportOfAxumWhereCodigoIsTheSkuAndCodbarraTheEan` and
  `theAxumHeadersAreRecognisedInUpperCaseAndInACsvToo`.
- Seven valid barcodes in Axum's export were rejected as invalid: they carry non-breaking spaces (U+00A0) before or after the
  digits, and `String.trim()` leaves those alone. Cells are now trimmed of Unicode spaces, zero-width characters and a stray
  BOM, at the ends only; no digit is ever guessed. `SpreadsheetReaderTest.nonBreakingSpacesAroundACellAreWhitespaceToo`.
- The gallery header listed the kinds of ignored rows from a list typed by hand that had fallen behind `RowProblem` (it left out
  a missing SKU and a repeated SKU), so a sheet with repeated SKUs told the person to look for the wrong cause. It is now derived
  from the kinds that are really there. `DeliveryWriterTest.theGalleryNamesEveryKindOfIgnoredRowThatIsThere`.
- The thread pool of a run was a fixed 8 per product in flight: with more than 8 stores the lookups would have queued silently and
  `--paralelo` would have stopped paying off. It is now the number of stores in `stores.txt` times `--paralelo`.
- Leftovers of the service model, found by an independent read of the Java modules: two fields of `PhotoFinder.StoreResult` that
  were filled and never read, comments in `StoreCatalogs`, `StoreProduct`, `Candidate` and the `fotos-core` description that
  promised an audit trail the report does not show, the usage text pointing at "the shortcut" instead of the launcher, and a
  README path that had lost its backslashes. The rest of that review (shared pixel helpers, a single User-Agent constant, tests
  that overlap) was left out on purpose: refactors with no urgency, one of them touching a header the stores see.

### Security
- Client article exports (they carry costs and price lists) sit in the repo root when dropped there: `/*.csv` is now git-ignored,
  next to the `*.xlsx` rule. It was caught before any commit, the file had never been staged.

## [0.2.0] - 2026-11-06

The project moved from a hosted service to a local drag-and-drop tool (decided 2026-11-06, see
`docs/proyecto/modelo-local.md`). The hosted pieces (`fotos-app`, the panel) are untouched and on hold.

### Added
- `fotos-tool`: the local tool. Drop an .xlsx or .csv with SKU and EAN on a launcher; it writes
  `<sheet> - fotos/` next to the sheet with `fotos/<SKU>.<jpg|png>`, `fotos.zip`, `reporte.csv` and
  `galeria.html`. A delivery folder is never reused, so a rerun cannot overwrite photos a person already swapped.
  `scripts/herramienta/instalar.ps1` installs it and creates the shortcut (it refuses to replace a folder that is
  not a previous install). Tests: `PhotoFinderTest` (the orchestration, with stores and network replaced: zero
  results, a download that fails midway, errors never stored as an answer), `BatchRunTest`, `MainTest`.
- `fotos-core`: spreadsheet reader (xlsx without a library, csv; columns by header name, not position),
  banner detector and cropper (Java port of the measured JS prototype: 711/712 detections and 670/671 crop outcomes
  agree on 791 real images), image quality checks, picture fingerprint, candidate picker, local cache, delivery
  writer. `SpreadsheetReaderTest`, `BannerCropperTest`, `ImageQualityTest`, `CandidatePickerTest`, `LocalCacheTest`,
  `DeliveryWriterTest` and more.
- `fotos-stores`: `SafeImageDownloader` (SSRF rules, size and time caps, type from the first bytes, redirects
  followed by hand with the same checks on each hop). `SafeImageDownloaderTest` covers a 200 that is HTML, an
  `application/octet-stream` that is a real image, a redirect to a non-public address and an oversized chunked body.
- The photo pick works in layers (`CandidatePicker`): not touching the frame, not soft, the majority picture among
  the stores (everyone votes, only the eligible can be chosen), then resolution, original over crop, size, store
  order. Measured on the 130-product catalog: the majority layer changes 22 picks, including the ones an independent
  review had flagged (a store carrying an older or different pack design; originals framed with a lot of air).

### Changed
- The store connector moved from `fotos-app` to the new `fotos-stores` module so the service and the tool share it.
  The client construction moved to `StoreHttp`. No behaviour change: same tests, same results.

### Fixed
- Banner cropper (spike): the first version took the largest connected component as the product, but a white pack
  breaks into several pieces, so it picked the panel as the product on 8 of 130 delivered photos and trimmed the
  product in about 40 more. The panel is now found by its shape (a solid strip with all the other content on one
  side) and the output is verified: a surviving panel is reported, not delivered. Locked in by
  `BannerCropperTest.aProductMadeOfSeveralSmallerPiecesIsKeptWholeEvenWhenThePanelIsTheBiggestBlob` and
  `aThinWidePieceAtTheFootOfTheProductIsNotAPanel`.
- Photos sliced at the frame by the source and a thumbnail enlarged to 2000 px were being delivered (3 and 1 of 124,
  found by an independent review). Locked in by `CandidatePickerTest`.

### Security
- The product list comes from a client: the xlsx is parsed with DTDs and external entities off and a size cap per zip
  entry (`SpreadsheetReaderTest.aFileThatTriesToReadALocalFileThroughAnXmlEntityLeaksNothing`).
- A SKU becomes a file name, so it is validated and never "cleaned" (`SkuFileName`): no separators, no `..`, no
  Windows device names. Cells written to `reporte.csv` that start with `=`, `+`, `-` or `@` are neutralised.
- Image URLs come from the stores: only http(s), every resolved address must be public, redirects re-checked,
  decoding refuses a declared canvas over 40 megapixels before allocating it.

## [0.1.0] - 2026-11-05

Never tagged or published: the skeleton of the hosted-service model, before the project became a local tool. It lives on in
`fotos-app`, frozen and untouched.

### Added
- Spring Boot service with the store connector (VTEX by EAN, with pacing, one retry and a circuit breaker), the embedded
  Next panel and an EAN lookup demo that is off by default and refuses to start unless the server listens on localhost.

<!--
Sections: Added / Changed / Fixed / Removed / Security.

One entry per change, with:
- what file or module it touched
- WHY it changed ("used X, which included currency purchases and triggered false positives")
- what test locks it in

Every bug that reached production closes with a test. Reference it here.
-->
