# Release accompanying license materials

Scope: NiroWear 0.1.0/code1 validation APK, SHA-256
`8b29475e2f75550ae16177107870bc8f28ee0fd3db226d4bcc801b775d94af72`.

Distribute this directory together with `THIRD_PARTY_NOTICES.md` as readable
materials accompanying that APK. These files do not modify the APK, grant a
license to NiroWear itself, approve publication, or close unrelated release gates.

`evidence.json` records exact resolved coordinates, artifact/POM/source hashes,
extracted license paths, copyright/header evidence, and pinned upstream files.
Repeated identical license texts are stored once, without changing their bytes.
`COPYRIGHT_HEADERS.txt` preserves distinct leading source comments from cached
exact-version source archives; it is supporting attribution, not a replacement
for the full license texts. Upstream NOTICE files are preserved verbatim.

Run `python docs/licenses/verify.py` to verify local license-material hashes and
the unchanged validation APK. Verification needs no build, emulator, credentials,
or network access. It checks bytes and coverage, not legal clearance.

## Boundaries and incomplete evidence

- Runtime coordinates come from the existing `releaseRuntimeClasspath` report.
  BOMs and multiplatform routing modules are distinguished from cached binaries;
  a coordinate in that graph is not proof that every class ships in the APK.
- Cached binaries, nested JARs, and source archives are inspected for license and
  NOTICE entries. Source-header inspection does not establish complete provenance
  of generated code or native libraries. Older AndroidX modules with no cached
  copyright header remain explicit gaps in the evidence inventory.
- DataStore's repackaged protobuf 1.2.1 embeds BSD terms without a copyright line;
  its sources JAR contains no Java sources. Supplemental protobuf upstream license
  includes Google copyright, but the exact protobuf source version incorporated by
  AndroidX has not been established. Do not treat that supplemental version as a
  resolved application dependency.
- AndroidX graphics-path includes native code. Its Java/Kotlin source archive does
  not establish complete native/Skia/NDK attribution. DataStore shared-counter
  native attribution likewise needs an exact native-source/build audit.
- Some OkHttp/Okio cached source headers refer to an Apache Software Foundation
  NOTICE. Their runtime archives and inspected version-pinned trees do not provide
  a matching standalone ASF NOTICE. Header preservation does not prove that gap
  closed; provenance and any applicable original NOTICE remain follow-up work.
- Kotlin's root NOTICE explicitly describes its compiler distribution. Preserve
  it as supplemental upstream evidence; do not claim the compiler is packaged.
  Runtime-relevant ThreeTenBP, Boost, GWT and Guava texts are supplied separately.
- OkHttp's Public Suffix List has MPL-2.0 terms. The supplied source list regenerates
  the exact packaged asset bytes; source availability applies to that covered list,
  not automatically to the independent NiroWear application.
- JLaTeXMath 0.2.0, its 30 fonts, MicroTeX, and optional Greek/Cyrillic artifacts are
  absent from this release. Debug JLaTeXMath is outside this notice bundle; its
  GPL/linking-exception/font/source review remains separate. Native release math
  implementation remains absent even if external licensing clearance exists.
- No blanket statement that every transitive, embedded, native, or toolchain
  component is legally cleared. Publication/signing/product acceptance unchanged.
