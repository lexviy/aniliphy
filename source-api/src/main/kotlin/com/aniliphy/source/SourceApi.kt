package com.aniliphy.source

/**
 * The contract between aniliphy and its extensions.
 *
 * An extension is an APK that declares, in its manifest:
 * - `<uses-feature android:name="aniliphy.extension" android:required="true" />`
 * - `<meta-data android:name="aniliphy.extension.class" android:value="<class implementing [Extension]>" />`
 * - `<meta-data android:name="aniliphy.extension.api" android:value="<[SourceApi.VERSION] it was built with>" />`
 *
 * It compiles against this module as `compileOnly`; the classes come from the app at runtime.
 */
object SourceApi {
    /** Raised whenever a change here would break extensions built against an older version. */
    const val VERSION = 1

    const val FEATURE = "aniliphy.extension"
    const val META_CLASS = "aniliphy.extension.class"
    const val META_API = "aniliphy.extension.api"
}

/** The entry point of an extension. Each list holds the sources it brings, and may be empty. */
interface Extension {
    fun anime(services: SourceServices): List<AnimeSite> = emptyList()

    fun manga(services: SourceServices): List<MangaSite> = emptyList()

    fun novels(services: SourceServices): List<NovelSite> = emptyList()
}

/** Common to every source. */
interface Site {
    /** Short and stable: it is saved with progress and synced, so it must never change. */
    val id: String

    /** Shown to the user. */
    val name: String
}
