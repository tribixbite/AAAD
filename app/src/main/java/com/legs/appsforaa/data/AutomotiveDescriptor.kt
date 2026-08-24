package com.legs.appsforaa.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.os.Build
import com.legs.appsforaa.utils.Logger
import org.xmlpull.v1.XmlPullParser

/**
 * Reads an app's `automotive_app_desc` — the XML its `com.google.android.gms.car.application`
 * meta-data points at — and reports which car experiences it declares.
 *
 * **This is different from Android Auto admission.** A descriptor tells Android Auto what kind of
 * experience an APK declares; the initiating installer and developer Unknown sources setting
 * still decide whether that declaration is admitted.
 *
 * Evidence for that split, from the shipped APKs of three catalog apps:
 *
 * | app | declares | behaviour |
 * | --- | --- | --- |
 * | CarStream | `service`, `projection`, `notification`, `media` | opens full screen |
 * | Fermata | `media`, `service`, `projection` | opens full screen |
 * | Nav2Contacts | `template` | runs as a templated app |
 * | AABrowser | `media` only | "can't use while driving" |
 *
 * Legacy `projection` apps may be exposed by Android Auto's developer option. Official Car App
 * Library `template` apps require a trusted initiating source for driving categories. AAAD cannot
 * manufacture that trust, so conversion creates a renamed, re-signed parked game copy instead.
 */
object AutomotiveDescriptor {

    private const val TAG = "AutoDescriptor"
    private const val AA_METADATA_KEY = "com.google.android.gms.car.application"

    /** The `<uses name="…"/>` values Android Auto understands, as seen in real APKs. */
    const val USES_PROJECTION = "projection"

    /**
     * A templated Car App Library app. Templates are distraction-optimised by construction, but a
     * game-category application is still parked-only. Nav2Contacts in the bundled catalog
     * declares exactly this descriptor and nothing else.
     */
    const val USES_TEMPLATE = "template"
    const val USES_MEDIA = "media"

    /**
     * What an app told Android Auto it can do.
     *
     * [uses] is reported verbatim rather than reduced to booleans so an unfamiliar value — a new
     * one Google adds, or a typo in a publisher's manifest — stays visible in diagnostics instead
     * of being silently dropped.
     */
    data class Capabilities(
        val uses: Set<String>,
        val appCategory: Int = ApplicationInfo.CATEGORY_UNDEFINED,
    ) {

        /** Declares a full-screen car Activity, the unofficial projected-app route. */
        val projects: Boolean get() = USES_PROJECTION in uses

        /** Declares a templated Car App Library app, the official route. */
        val templated: Boolean get() = USES_TEMPLATE in uses

        /**
         * Declares some car surface. This does not imply Android Auto will admit it.
         */
        val hasCarUi: Boolean get() = projects || templated

        /** Games are an Android Auto parked-app category and are unavailable while driving. */
        val parkedOnly: Boolean get() = appCategory == ApplicationInfo.CATEGORY_GAME

        /** A declared non-game car surface; trusted-source admission is a separate check. */
        val hasDrivingUi: Boolean get() = hasCarUi && !parkedOnly

        /**
         * Declares Android Auto support but only as a media source. Such an app appears in the
         * launcher and then refuses to open its own UI in the car — the exact symptom AABrowser
         * shows.
         */
        val mediaOnly: Boolean get() = uses == setOf(USES_MEDIA)

        val isEmpty: Boolean get() = uses.isEmpty()
    }

    /** Capabilities of an installed package, or null when it declares no Android Auto metadata. */
    fun forInstalled(packageManager: PackageManager, packageName: String): Capabilities? =
        runCatching {
            val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            read(packageManager, info)
        }.onFailure {
            Logger.w(TAG, "Could not read the car descriptor of $packageName", it)
        }.getOrNull()

    /**
     * Capabilities of an APK file that is **not installed**, so a download can be judged before it
     * is committed.
     *
     * `getPackageArchiveInfo` leaves `sourceDir` unset, and `getResourcesForApplication` needs it
     * to build an AssetManager over the archive; setting both it and `publicSourceDir` is what
     * makes reading resources out of a loose APK work at all.
     */
    fun forApkFile(packageManager: PackageManager, apkPath: String): Capabilities? =
        runCatching {
            val packageInfo = packageManager.getPackageArchiveInfo(
                apkPath, PackageManager.GET_META_DATA
            ) ?: return null
            val info = packageInfo.applicationInfo ?: return null
            info.sourceDir = apkPath
            info.publicSourceDir = apkPath
            read(packageManager, info)
        }.onFailure {
            Logger.w(TAG, "Could not read the car descriptor of $apkPath", it)
        }.getOrNull()

    private fun read(packageManager: PackageManager, info: ApplicationInfo): Capabilities? {
        // The meta-data is android:resource, so the bundle holds a resource id, not a string.
        val resourceId = info.metaData?.getInt(AA_METADATA_KEY, 0) ?: 0
        if (resourceId == 0) return null

        val resources: Resources = packageManager.getResourcesForApplication(info)
        val uses = resources.getXml(resourceId).use { parser -> parseUses(parser) }
        val category = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            info.category
        } else {
            ApplicationInfo.CATEGORY_UNDEFINED
        }
        return Capabilities(uses, category)
    }

    /**
     * Collects every `<uses name="…"/>`.
     *
     * The `name` attribute carries **no namespace** in these descriptors — it is plain `name=`,
     * not `android:name=` — so it must be looked up with a null namespace. Asking for the android
     * namespace returns null for every real descriptor and reports each app as declaring nothing.
     */
    private fun parseUses(parser: XmlResourceParser): Set<String> {
        val uses = mutableSetOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "uses") {
                parser.getAttributeValue(null, "name")?.takeIf { it.isNotBlank() }?.let(uses::add)
            }
            event = parser.next()
        }
        return uses
    }

    /** [XmlResourceParser] is not [AutoCloseable] before API 31, so `use` is spelled by hand. */
    private inline fun <R> XmlResourceParser.use(block: (XmlResourceParser) -> R): R =
        try {
            block(this)
        } finally {
            close()
        }
}
