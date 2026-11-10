package com.aniliphy.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.aniliphy.source.Extension
import com.aniliphy.source.SourceApi
import dalvik.system.PathClassLoader
import java.security.MessageDigest

/** An installed APK that says it is an aniliphy extension, loaded or not. */
data class InstalledExtension(
    val packageName: String,
    val label: String,
    val version: String,
    val api: Int?,
    /** SHA-256 of the certificate the APK is signed with. */
    val signer: String?,
    val trusted: Boolean,
    /** Signed with the same key as aniliphy, so trusted without asking. */
    val sameKey: Boolean,
    /** Why it wasn't loaded, or null when it was. */
    val problem: String?,
    /** The names of the sources it brings. */
    val sources: List<String> = emptyList(),
)

/**
 * Finds and loads the extensions installed on the phone.
 *
 * Their code runs inside aniliphy, so only extensions signed with the same key as the app, or whose key
 * the user trusted, are loaded.
 */
object Extensions {
    class Found(val info: InstalledExtension, val extension: Extension?)

    private const val PREFS = "extensions"
    private const val TRUSTED = "trusted_signers"

    fun find(context: Context): List<Found> {
        val pm = context.packageManager
        val own = runCatching { signerOf(packageInfo(pm, context.packageName)) }.getOrNull()
        val trusted = trustedSigners(context)
        return installed(pm)
            .filter { info -> info.reqFeatures.orEmpty().any { it.name == SourceApi.FEATURE } }
            .map { info -> load(context, pm, info, own, trusted) }
            .sortedBy { it.info.label.lowercase() }
    }

    fun trust(context: Context, signer: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(TRUSTED, trustedSigners(context) + signer).apply()
    }

    fun distrust(context: Context, signer: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(TRUSTED, trustedSigners(context) - signer).apply()
    }

    private fun trustedSigners(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(TRUSTED, null).orEmpty().toSet()

    private fun load(context: Context, pm: PackageManager, info: PackageInfo, own: String?, trusted: Set<String>): Found {
        val app = info.applicationInfo
        val meta = app?.metaData
        val api = meta?.get(SourceApi.META_API)?.toString()?.toIntOrNull()
        val signer = runCatching { signerOf(info) }.getOrNull()
        val isTrusted = signer != null && (signer == own || signer in trusted)
        fun found(problem: String?, extension: Extension? = null) = Found(
            InstalledExtension(
                packageName = info.packageName,
                label = app?.loadLabel(pm)?.toString() ?: info.packageName,
                version = info.versionName.orEmpty(),
                api = api,
                signer = signer,
                trusted = isTrusted,
                sameKey = signer != null && signer == own,
                problem = problem,
            ),
            extension,
        )
        if (app == null) return found("It couldn't be read")
        if (!isTrusted) return found("It isn't trusted")
        if (api == null || api < 1) return found("It doesn't say which version of aniliphy it was made for")
        if (api > SourceApi.VERSION) return found("It needs a newer aniliphy")
        val name = meta.getString(SourceApi.META_CLASS)?.let { if (it.startsWith(".")) info.packageName + it else it }
            ?: return found("It doesn't say where its sources are")
        return try {
            val loader = PathClassLoader(app.sourceDir, app.nativeLibraryDir, context.classLoader)
            val extension = Class.forName(name, true, loader).getDeclaredConstructor().newInstance() as Extension
            found(null, extension)
        } catch (e: Throwable) {
            found("It failed to load: ${e.javaClass.simpleName}${e.message?.let { " ($it)" }.orEmpty()}")
        }
    }

    @Suppress("DEPRECATION")
    private fun flags(): Int {
        val signing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA or signing
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun installed(pm: PackageManager): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags())
        }

    private fun packageInfo(pm: PackageManager, name: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(name, PackageManager.PackageInfoFlags.of(flags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(name, flags())
        }

    private fun signerOf(info: PackageInfo): String? {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val first = signatures?.singleOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(first.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
