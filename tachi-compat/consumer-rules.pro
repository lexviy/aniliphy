# Extensions are loaded at runtime and use these by name, so keep them whole.
-keep class eu.kanade.tachiyomi.** { *; }
-keep class tachiyomi.core.** { *; }
-keep class mihon.core.** { *; }
-keep class uy.kohesive.injekt.** { *; }
-keep class rx.** { *; }
-keep,allowoptimization class androidx.preference.** { public protected *; }
-dontwarn rx.internal.util.unsafe.**

# Injekt reads the injected type from the generic superclass of its type tokens.
-keepattributes Signature
-keep,allowshrinking class * extends uy.kohesive.injekt.api.FullTypeReference
-keep,allowoptimization class com.squareup.zstd.** { public protected *; }
