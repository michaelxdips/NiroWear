# NiroWear third-party notices — release accompanying materials

## Current posture - 2026-10-22

First-party NiroWear application source is licensed **MIT** (see LICENSE).
Copyright (c) 2026 Stephen Michael. Third-party components retain their own rights and are
**not** relicensed by that grant; the sections below remain authoritative for them.

The only non-permissive build-time dependency is `ru.noties:jlatexmath-android:0.2.0`
(GPL v2-or-later with an independent-module linking exception). It is a `debugImplementation`
only: it is absent from release builds, which use the source-fallback math renderer, and this
repository does not redistribute its binaries or sources. Anyone enabling native math in a
distributed build inherits that dependency's obligations themselves.

APK releases remain at https://github.com/michaelxdips/NiroWear-Beta and carry their own
notices inside `assets/legal/`. This repository publishes source, not releases.
## Historical code1 scope

These notices cover the existing NiroWear 0.1.0/code1 validation release identified
below. They do not license NiroWear's own code, approve distribution or publication,
or waive native math and other product acceptance requirements.

**Keep this file and the entire `docs/licenses/` directory together with every
distribution of this identified APK.** The license texts, copyright excerpts,
upstream NOTICE files and MPL-covered source are accompanying materials, not new
APK assets. No APK/source/build/signing change is part of this notice preparation.

- APK: `artifacts/software-rc-2026-10-09/ultimate-audit/final-release/app-release-validation.apk`
- APK SHA-256: `8b29475e2f75550ae16177107870bc8f28ee0fd3db226d4bcc801b775d94af72`
- Graph: `artifacts/software-rc-2026-10-09/autonomous-closure/release-handoff/release-dependencies.txt`
- Graph SHA-256: `77675b36aceebdc1c68207a47280e6849a1827644428fbeb2decf26cceeb0f55`
- Inventory: [exact coordinates, sources and SHA-256 evidence](docs/licenses/evidence.json).
  This covers 145 non-constraint coordinates in the existing runtime report,
  including metadata/BOM/multiplatform routing modules, not 145 packaged libraries.

## CommonMark and autolink

| Component | Resolved version | Full copyright/license text |
|---|---|---|
| `org.commonmark:commonmark` | 0.24.0 | [BSD-2-Clause](docs/licenses/COMMONMARK-BSD-2-Clause.txt) |
| `org.commonmark:commonmark-ext-gfm-tables` | 0.24.0 | Same exact bundled BSD-2-Clause text |
| `org.commonmark:commonmark-ext-autolink` | 0.24.0 | Same exact bundled BSD-2-Clause text |
| `org.nibor.autolink:autolink` | 0.11.0 | [MIT](docs/licenses/AUTOLINK-MIT.txt) |

CommonMark: Copyright (c) 2015, Atlassian Pty Ltd. All rights reserved.
Autolink: Copyright (c) 2015 Robin Stocker.

CommonMark text is copied byte-for-byte from each exact runtime JAR's
`META-INF/LICENSE.txt`; SHA-256
`11cfcf6da0142fb1971c1f40f5924ddded14454e2793d7f1dd87c9f4abe42dfd`.
Autolink text is copied from release-tag commit
`09715b958ec942736e53ac4f3054f06acf91ed60`; SHA-256
`2d7b0f54def673747d75e8399c26f313ac1ee3e00dc86d2c488ebd0b790bb022`.
These full texts supply the previously missing accompanying BSD/MIT notices.

## AndroidX, Compose, Wear and Material icons

AndroidX exact POMs declare Apache License 2.0 except repackaged protobuf below.
[Exact cached AndroidX Apache text](docs/licenses/LICENSE-809fa1ed2145.txt)
preserves the supplied complete terms. [Copyright/header excerpts](docs/licenses/COPYRIGHT_HEADERS.txt)
preserve observed Android Open Source Project, JetBrains and other source notices
with their source coordinates. Do not infer one copyright year for all modules.

| Runtime family | Resolved versions |
|---|---|
| Activity / Activity Compose / Activity KTX | 1.13.0 |
| Lifecycle, including Compose/runtime/viewmodel/savedstate/livedata variants | 2.10.0 |
| Compose animation/foundation/runtime/UI/material-ripple and Android variants | 1.11.2 |
| Compose material-icons-core and Android variant | 1.7.8 |
| Compose BOM (metadata, not runtime code) | 2026.05.01 |
| Wear Compose foundation/material-core/material3 | 1.6.2 |
| Room runtime/common/KTX and JVM/Android variants | 2.8.4 |
| DataStore/preferences/core/Okio/proto and JVM/Android variants | 1.2.1 |
| SQLite/framework and Android variants | 2.6.2 |
| Annotation / annotation-jvm; annotation-experimental | 1.9.1; 1.5.0 |
| Collection / collection-jvm / collection-ktx | 1.5.0 |
| Core / core-ktx; core-viewtree | 1.18.0; 1.0.0 |
| Arch core-common/core-runtime | 2.2.0 |
| SavedState/Compose/KTX and Android variants | 1.4.0 |
| NavigationEvent/Compose and Android variants | 1.0.0 |
| Window/core and Android variant | 1.5.0 |
| Graphics-path; graphics-shapes and Android variant | 1.0.1 |
| Concurrent-futures | 1.1.0 |
| Emoji2 | 1.4.0 |
| ProfileInstaller | 1.4.0 |
| Startup; tracing | 1.2.0 |
| Transition | 1.6.0 |
| VersionedParcelable | 1.1.1 |
| Autofill, customview-poolingcontainer, documentfile, dynamicanimation, interpolator, legacy-support-core-utils, loader, localbroadcastmanager, print | 1.0.0 |

Material input vectors are Google's Material Icons, not bundled icon fonts.
[Full Apache license](docs/licenses/MATERIAL_ICONS_LICENSE.txt) and
[original packaged attribution/modification notice](docs/licenses/MATERIAL_ICONS_NOTICE.txt)
are copied unchanged from the APK. Notice identifies keyboard/microphone SVGs,
SVG-to-VectorDrawable conversion and removal of invisible background paths.

### DataStore repackaged protobuf

`androidx.datastore:datastore-preferences-external-protobuf:1.2.1` declares
BSD-3-Clause. [Exact embedded terms](docs/licenses/DATASTORE-PROTOBUF-BSD-3-Clause.txt)
are preserved unchanged. They omit a copyright line.
[Supplemental upstream protobuf license](docs/licenses/PROTOBUF-UPSTREAM-SUPPLEMENTAL.txt)
includes `Copyright 2008 Google Inc. All rights reserved.` from pinned protobuf
v3.25.5 source. **Exact protobuf version embedded by AndroidX is unresolved**;
v3.25.5 is supplemental evidence, not an asserted resolved dependency.

## Kotlin runtime, coroutines and serialization

| Runtime family | Resolved version | Full texts |
|---|---|---|
| `org.jetbrains.kotlin:kotlin-stdlib`, stdlib-common metadata | 2.3.21 | [Apache](docs/licenses/KOTLIN-LICENSE.txt), [copyright](docs/licenses/KOTLIN-COPYRIGHT.txt), [supplemental upstream NOTICE](docs/licenses/KOTLIN-UPSTREAM-NOTICE.txt) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android`, core/core-jvm, BOM | 1.9.0 | [Apache](docs/licenses/COROUTINES-LICENSE.txt), [NOTICE](docs/licenses/COROUTINES-NOTICE.txt) |
| `org.jetbrains.kotlinx:kotlinx-serialization-core`, core-jvm, json/json-jvm, BOM | 1.11.0 | [Apache](docs/licenses/SERIALIZATION-LICENSE.txt), [NOTICE](docs/licenses/SERIALIZATION-NOTICE.txt) |

All texts come from exact release-tag commits recorded in the evidence inventory.
Kotlin's root NOTICE describes the **compiler distribution**; preserving it does
not mean compiler tooling ships in the APK. Coroutines and serialization NOTICE
texts are retained verbatim, including upstream spelling/year ranges.

Kotlin JVM standard library also contains code derived from:

- ThreeTenBP time code: Copyright (c) 2007-present, Stephen Colebourne & Michael
  Nascimento Santos; [full BSD-3-Clause terms](docs/licenses/KOTLIN-THREETENBP-BSD-3-Clause.txt).
- Boost special math functions: Copyright Eric Ford & Hubert Holin 2001;
  [full Boost Software License 1.0](docs/licenses/KOTLIN-BOOST-1.0.txt).
- GWT collections: Copyright 2007 Google Inc.; Apache 2.0.
- Guava unsigned arithmetic: Copyright 2011 The Guava Authors; Apache 2.0.

GWT/Guava license texts are byte-identical to `KOTLIN-LICENSE.txt` and deduplicated
there. [Version-pinned upstream scope document](docs/licenses/KOTLIN-LICENSE-SCOPE.md)
and cached copyright excerpts identify the relevant runtime paths. Its other
compiler/test/JS/Wasm dependencies are not claimed as Android runtime components.

## HTTP, I/O and annotation/future transitives

| Component | Resolved version | License / attribution evidence |
|---|---|---|
| `com.squareup.okhttp3:okhttp`, `okhttp-android` | 5.3.2 | [Apache 2.0](docs/licenses/KOTLIN-LICENSE.txt); exact cached source headers retain Square, Block, Android Open Source Project, Twitter and ASF notices |
| `com.squareup.okio:okio`, `okio-jvm` | 3.16.4 | [Apache 2.0](docs/licenses/KOTLIN-LICENSE.txt); Square/other cached notices preserved |
| `org.jetbrains:annotations` | 23.0.0 | [Apache 2.0](docs/licenses/JETBRAINS-ANNOTATIONS-LICENSE.txt); Copyright 2000-2020/2021 JetBrains s.r.o. in exact sources |
| `org.jspecify:jspecify` | 1.0.0 | [Apache 2.0](docs/licenses/KOTLIN-LICENSE.txt); Copyright 2018-2020 / 2022 The JSpecify Authors in exact sources |
| `com.google.guava:listenablefuture` | 1.0 | [Apache 2.0](docs/licenses/KOTLIN-LICENSE.txt); Copyright (C) 2007 The Guava Authors in exact source |

OkHttp/Okio/JSpecify pinned license files are byte-identical to the linked Apache
text. This is license-text deduplication, not a claim that Kotlin licenses those
independent projects. OkHttp/Okio POMs name `square` upstream URLs; inspected URLs
redirect to `lysine-dev` repositories. Pinned commits, requested/final URLs and
exact cached artifact hashes are recorded; repository/package provenance is not
independently attested. No matching standalone ASF NOTICE was found in those
cached archives/inspected pinned trees; retained ASF source headers remain a
follow-up provenance/NOTICE question, not fabricated clearance.

### Public Suffix List — MPL-2.0 covered data

OkHttp bundles `assets/PublicSuffixDatabase.list`, SHA-256
`c75ab827afd7ffff59a51b14729c25dbfc3b45237eb40ccf02bec7b7d44304a4`.
This asset is compiled from the Public Suffix List and has separate MPL-2.0 terms.

- [Full Mozilla Public License 2.0](docs/licenses/MPL-2.0.txt).
- [Unchanged upstream PSL NOTICE](docs/licenses/PUBLIC_SUFFIX_LIST-NOTICE.txt).
- [Accompanying source list](docs/licenses/PUBLIC_SUFFIX_LIST.dat), version
  `2024-11-26_09-06-27_UTC`, list commit `bf32ba1ae6228a185ebf297d636f1c6541149c45`.
- [Version-pinned upstream generator](docs/licenses/PUBLIC_SUFFIX_LIST_GENERATOR.kt),
  retained for source/build evidence, not added application code.

The supplied source list regenerates the exact packaged asset bytes using sorted
UTF-8 rules/exception rules and big-endian lengths. Its source SHA-256 is
`986879fc41f0a5cc65f5e7d88fa68f6f0d9c366bb620418b8b8045f61a134077`.
MPL-covered list source is provided here; no claim that this requires licensing
independent NiroWear application code under MPL.

## Inventory boundaries and remaining proof

[README](docs/licenses/README.md) lists incomplete evidence. Native graphics-path/
DataStore attribution, embedded protobuf provenance, older source-header gaps,
ASF NOTICE provenance and complete embedded/generated-code mapping remain open.
Cached binary license entries and distinct copyright-bearing source comments are
preserved; POM license labels alone are not blanket compliance approval.

JLaTeXMath 0.2.0 and its fonts remain **debug-only and excluded from this release**.
No GPL/font candidate is promoted or copied into these release materials.
External math clearance and native release implementation are separate questions.
MicroTeX, optional math font artifacts, compiler/build tooling and Android system
fonts are not represented as application runtime redistributions here.

Offline integrity check: `python docs/licenses/verify.py`. It verifies supplied
texts/coverage and unchanged APK/report bytes, not legal or store approval.
