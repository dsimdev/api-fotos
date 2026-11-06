# Changelog

All notable changes to this project. Technical detail, in English — the audience is future-you
and whoever runs the next 360 audit. Write **why**, not just what.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) · Versioning:
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- The delivery folder is now `Fotos <date>` (e.g. `Fotos 2026-11-06`) created INSIDE the folder the program is installed
  in, no longer next to the dropped sheet: the sheet usually comes from Downloads and that is not where deliveries should
  pile up. A new `--salida <folder>` option puts it elsewhere; run from a build rather than an install it still writes
  next to the sheet. A second run the same day is `Fotos <date> (2)`, never a reuse. `DeliveryWriter.newFolderNextTo`
  became `newFolder(root, name)`; the name is chosen by the caller, not taken from the client sheet. Locked in by
  `MainTest` and `DeliveryWriterTest.theDeliveryFolderIsNeverReused`.
- `scripts/herramienta/instalar.ps1` installs to `C:\Dev\Fotos Tienda` and no longer creates a desktop shortcut (each person
  makes theirs). Because deliveries now live in the install folder, an update replaces only `bin`, `lib` and the launcher
  and never wipes the folder; it still refuses a non-empty folder that is not a previous install.
- A row with no barcode now says "No tiene código de barras (EAN) en la planilla" instead of "EAN inválido": in an export of
  a client whose articles have no barcode loaded, that is the common case and "invalid" sent people looking for a typo.
  `BatchRunTest.aRowWithoutAnEanSaysItIsMissingInsteadOfInvalid`.

### Fixed
- Axum's article export was rejected ("no encontré las columnas SKU y EAN"): it names the barcode column `codbarra`, singular,
  and only `codbarras` was recognised. The singular forms were added; `codigo` was already the SKU.
  `SpreadsheetReaderTest.readsTheArticleExportOfAxumWhereCodigoIsTheSkuAndCodbarraTheEan` and
  `theAxumHeadersAreRecognisedInUpperCaseAndInACsvToo`.

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

## [0.1.0] - YYYY-MM-DD

### Added
- Initial project scaffold.

<!--
Sections: Added / Changed / Fixed / Removed / Security.

One entry per change, with:
- what file or module it touched
- WHY it changed ("used X, which included currency purchases and triggered false positives")
- what test locks it in

Every bug that reached production closes with a test. Reference it here.
-->
