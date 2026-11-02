# Third-party notices

Socle's distributed files bundle the software below, each under its own licence. The licence
texts are in [legal/](legal/) (and inside the jars, under `socle-legal/`); the AGPL-3.0 does not
apply to them.

- **`socle-X.jar`** (standalone app and CLI) holds everything under "JVM" below.
- **`socle-lsp-X.jar`** (language server) holds the same, plus "Language server only".
- **`socle-X.html`** (single-file UI, also served by both jars) holds "Browser UI".

## JVM

| Component | Version | Licence |
|---|---|---|
| Scala standard library, scala-reflect | 2.13.16 | Apache-2.0 |
| scala-xml, scala-collection-compat | 2.2.0, 2.11.0 | Apache-2.0 |
| Chisel, firtool-resolver (CHIPS Alliance) | 7.16.0, 2.1.1 | Apache-2.0 |
| json4s | 4.1.0 | Apache-2.0 |
| data-class | 0.2.7 | Apache-2.0 |
| Apache Commons Lang, Commons Text | 3.20.0, 1.15.0 | Apache-2.0 |
| Guava, failureaccess, error_prone_annotations, j2objc-annotations | 33.7.1, 1.0.3, 2.50.0, 3.1 | Apache-2.0 |
| Gson | 2.14.0 | Apache-2.0 |
| JSpecify | 1.0.1 | Apache-2.0 |
| upickle, ujson, upack, os-lib, geny, sourcecode (Li Haoyi) | 4.0.2, 0.11.4, 1.1.1, 0.4.4 | MIT |
| scopt | 4.1.0 | MIT |
| Eclipse Layout Kernel (ELK) | 0.9.1 | EPL-2.0 |
| Eclipse Modeling Framework (EMF) common, ecore, ecore.xmi | 2.12.0 | EPL-1.0 |

## Language server only

| Component | Version | Licence |
|---|---|---|
| Eclipse LSP4J, LSP4J JSON-RPC | 1.0.0 | EPL-2.0 |
| scalameta (common, io, parsers, trees) | 4.17.4 | BSD-3-Clause |
| portable-scala-reflect | 1.1.3 | BSD-3-Clause |
| Byte Buddy (shaded inside the probe agent) | 1.18.15 | Apache-2.0 |
| Surfer, built to WebAssembly (`surfer/`) | 0.7.0 | EUPL-1.2 (`surfer/LICENSE-EUPL-1.2.txt`, `surfer/SOURCE.md`) |

## Browser UI

| Component | Version | Licence |
|---|---|---|
| elkjs | 0.9.1 | EPL-2.0 |
| IBM Plex Sans, IBM Plex Mono (fonts) | | SIL Open Font License 1.1 (Copyright 2017 IBM Corp., Reserved Font Name "Plex") |
| Scala.js library | 1.19.0 | Apache-2.0 |
| Laminar, Airstream, ew (Nikita Gazarov) | 17.2.0, 17.2.0, 0.2.0 | MIT |
| tuplez | 0.4.0 | MIT |
| scala-js-dom | 2.8.0 | MIT |
| upickle, ujson, upack, geny (Li Haoyi) | 4.0.2, 1.1.1 | MIT |

## Licence texts

- **[Apache-2.0](legal/Apache-2.0.txt):** the NOTICE files of these components are kept in the jars' `META-INF/`.
- **[EPL-2.0](legal/EPL-2.0.txt) and [EPL-1.0](legal/EPL-1.0.txt):**
  - the source code of ELK, elkjs, EMF and LSP4J is available from the Eclipse Foundation
    (https://github.com/eclipse/elk, https://github.com/kieler/elkjs,
    https://github.com/eclipse-emf, https://github.com/eclipse-lsp4j/lsp4j);
  - Socle uses them unmodified.
- **[MIT](legal/MIT.txt)** and **[BSD-3-Clause](legal/BSD-3-Clause.txt):** with each copyright holder.
- **[SIL Open Font License 1.1](legal/OFL-1.1-IBM-Plex.txt):** IBM Plex.
- **EUPL-1.2:** Surfer, inside `socle-lsp-X.jar` (and in `socle-lsp/vendor/surfer/`).
