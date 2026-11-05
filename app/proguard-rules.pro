# NiroWear R8 rules.
#
# This file exists because release builds already run R8 ("optimization { enable = true }") with no
# repository-controlled rules at all, which means:
#   - obfuscation was on with no stated policy anywhere in the tree, and
#   - the generated mapping.txt was written to build/ and never shipped, so every obfuscated crash
#     report from a user was unreadable.
#
# Only what is actually needed is declared. Room ships its own consumer rule for the generated
# *_Impl constructor it looks up by name, and okhttp-android ships one too; the app uses
# kotlinx.serialization only as a JSON tree API (no @Serializable, so no reflective serializer
# lookup) and calls JLaTeXMath directly rather than by name.

# Keep line numbers: a stack trace is the only diagnostic this app can get, since it ships no crash
# reporter. Renaming the source file is fine and keeps some size back.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The Keystore and SQLite driver are platform classes reached through reflective factories; their
# names must survive. (Their AARs' consumer rules cover the library side.)
-keep class androidx.sqlite.db.framework.** { *; }