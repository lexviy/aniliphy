-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**

-keep class com.aniliphy.source.** { *; }

# Extensions run with the app's copies of these libraries, so keep their names and public members.
-keep,allowoptimization class kotlin.** { public protected *; }
-keep,allowoptimization class kotlinx.coroutines.** { public protected *; }
-keep,allowoptimization class kotlinx.serialization.** { public protected *; }
-keep,allowoptimization class okhttp3.** { public protected *; }
-keep,allowoptimization class okio.** { public protected *; }
-keep,allowoptimization class org.jsoup.** { public protected *; }
