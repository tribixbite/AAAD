package com.legs.appsforaa.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.legs.appsforaa.BuildConfig
import com.legs.appsforaa.R
import com.legs.appsforaa.data.AutomotiveDescriptor
import com.legs.appsforaa.data.ConversionState
import com.legs.appsforaa.data.InstalledApp
import com.reandroid.apkeditor.Main
import com.reandroid.archive.ZipAlign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Turns an ordinary installed app into a side-by-side Android Auto car copy on the phone.
 *
 * This is intentionally a clone, never an update over the publisher package: manifest/resource
 * changes invalidate the publisher signature. The original app and its data stay untouched while
 * the clone is signed with [CarifySigner]. Shizuku can install it unattended; Android's installer
 * is the confirmation-based fallback.
 */
class CarifyRepackager(private val context: Context) {

    enum class InstallMode {
        SHIZUKU,
        SYSTEM,
    }

    enum class Stage(val percent: Int) {
        PREPARING(5),
        MERGING(12),
        READING(24),
        PATCHING(38),
        BUILDING(55),
        SIGNING(78),
        STAGING(88),
        INSTALLING(94),
        WAITING_FOR_CONFIRMATION(96),
    }

    sealed interface Result {
        data class Success(
            val packageName: String,
            val usedSystemInstaller: Boolean = false,
        ) : Result
        data class Failure(val message: String) : Result
    }

    private companion object {
        const val TAG = "CarifyRepackager"
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val AA_METADATA = "com.google.android.gms.car.application"
        const val PROJECTION_CATEGORY =
            "com.google.android.gms.car.category.CATEGORY_PROJECTION"
        const val CAR_APP_SERVICE_ACTION = "androidx.car.app.CarAppService"
    }

    /**
     * Creates a car-compatible clone directly from a downloaded APK.
     *
     * Catalog installs use this before the publisher package is installed, so a media-only or
     * parked-only APK never enters Android Auto as the misleading, unusable launcher entry.
     */
    suspend fun convertApk(
        apk: File,
        displayLabel: String,
        installMode: InstallMode = InstallMode.SHIZUKU,
        onProgress: (Stage) -> Unit = {},
    ): Result {
        if (!apk.isFile || apk.length() == 0L) {
            return Result.Failure("Downloaded APK is missing or empty")
        }
        val packageInfo = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_META_DATA,
        ) ?: return Result.Failure("Downloaded file is not a readable APK")
        val packageName = packageInfo.packageName
        if (packageName.isBlank()) return Result.Failure("Downloaded APK has no package name")

        val input = InstalledApp(
            packageName = packageName,
            label = displayLabel,
            versionName = packageInfo.versionName.orEmpty(),
            installerPackage = null,
            initiatingPackage = null,
            apkPaths = listOf(apk.absolutePath),
            state = ConversionState.CONVERTIBLE,
            carCapabilities = AutomotiveDescriptor.forApkFile(
                context.packageManager,
                apk.absolutePath,
            ),
        )
        return convert(input, installMode, onProgress)
    }

    suspend fun convert(
        app: InstalledApp,
        installMode: InstallMode = InstallMode.SHIZUKU,
        onProgress: (Stage) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return@withContext Result.Failure(
                context.getString(R.string.carify_requires_android_15)
            )
        }
        val suffix = if (BuildConfig.DEBUG) ".aaaddev" else ".aaad"
        val clonePackage = app.packageName + suffix
        val work = File(
            context.cacheDir,
            "carify/" + app.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_") +
                "-" + System.nanoTime(),
        )

        try {
            onProgress(Stage.PREPARING)
            work.mkdirs()
            Logger.i(TAG, "Carifying ${app.packageName} -> $clonePackage")
            if (app.isSplit) onProgress(Stage.MERGING)
            val original = prepareBaseApk(app, work)
            currentCoroutineContext().ensureActive()
            val decoded = File(work, "decoded")

            onProgress(Stage.READING)
            runEditor(
                "decode",
                "d", "-i", original.absolutePath, "-o", decoded.absolutePath,
                "-t", "xml", "-dex", "-f",
            )
            currentCoroutineContext().ensureActive()
            onProgress(Stage.PATCHING)
            patchManifest(
                File(decoded, "AndroidManifest.xml"),
                app.packageName,
                clonePackage,
                app.label,
            )
            currentCoroutineContext().ensureActive()

            val unsigned = File(work, "unsigned.apk")
            onProgress(Stage.BUILDING)
            runEditor(
                "build",
                "b", "-i", decoded.absolutePath, "-o", unsigned.absolutePath,
                "-t", "xml", "-f",
            )
            currentCoroutineContext().ensureActive()

            val aligned = File(work, "aligned.apk")
            ZipAlign.alignApk(unsigned, aligned)
            val signed = File(work, "clone.apk")
            onProgress(Stage.SIGNING)
            CarifySigner.sign(aligned, signed)
            currentCoroutineContext().ensureActive()

            val archive = context.packageManager.getPackageArchiveInfo(signed.absolutePath, 0)
                ?: error("Generated APK cannot be parsed")
            check(archive.packageName == clonePackage) {
                "Generated package is ${archive.packageName}, expected $clonePackage"
            }

            when (installMode) {
                InstallMode.SHIZUKU -> {
                    onProgress(Stage.INSTALLING)
                    when (val installed = ShizukuInstaller.install(signed)) {
                        is ShizukuInstaller.Result.Success -> {
                            Logger.i(TAG, "Installed car clone $clonePackage")
                            Result.Success(clonePackage)
                        }
                        is ShizukuInstaller.Result.Failure -> Result.Failure(installed.message)
                    }
                }
                InstallMode.SYSTEM -> {
                    onProgress(Stage.STAGING)
                    when (
                        val installed = SystemInstaller.installAndAwait(
                            context,
                            listOf(signed),
                            onAwaitingConfirmation = {
                                onProgress(Stage.WAITING_FOR_CONFIRMATION)
                            },
                        )
                    ) {
                        SystemInstaller.AwaitedResult.Installed -> {
                            Logger.i(TAG, "Installed car clone $clonePackage via system installer")
                            Result.Success(clonePackage, usedSystemInstaller = true)
                        }
                        is SystemInstaller.AwaitedResult.Failure ->
                            Result.Failure(installed.message)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            Logger.i(TAG, "Carify cancelled for ${app.packageName}")
            throw cancelled
        } catch (error: Throwable) {
            Logger.e(TAG, "Carify failed for ${app.packageName}", error)
            Result.Failure(error.message ?: error.javaClass.simpleName)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun prepareBaseApk(app: InstalledApp, work: File): File {
        if (app.apkPaths.size == 1) {
            return File(work, "original.apk").also { destination ->
                File(app.apkPaths.single()).inputStream().use { input ->
                    destination.outputStream().use(input::copyTo)
                }
            }
        }

        val splits = File(work, "splits").apply { mkdirs() }
        app.apkPaths.forEachIndexed { index, path ->
            val name = if (index == 0) "base.apk" else "split_$index.apk"
            File(path).inputStream().use { input ->
                File(splits, name).outputStream().use(input::copyTo)
            }
        }
        val merged = File(work, "original.apk")
        runEditor(
            "split merge",
            "m", "-i", splits.absolutePath, "-o", merged.absolutePath,
            "-clean-meta", "-f",
        )
        return merged
    }

    private fun patchManifest(
        file: File,
        oldPackage: String,
        newPackage: String,
        displayLabel: String,
    ) {
        val document = readXml(file)
        val root = document.documentElement
        root.setAttribute("package", newPackage)
        root.removeAttributeNS(ANDROID_NS, "sharedUserId")
        root.removeAttributeNS(ANDROID_NS, "sharedUserLabel")

        val identifierAttrs = listOf(
            "authorities",
            "taskAffinity",
            "permission",
            "targetPackage",
            "process",
        )
        for (element in root.allElements()) {
            for (attribute in identifierAttrs) {
                val value = element.android(attribute)
                if (value.isBlank()) continue
                val renamed = value.split(";").joinToString(";") {
                    renameIdentifier(it, oldPackage, newPackage)
                }
                if (renamed != value) element.setAndroid(attribute, renamed)
            }
        }

        for (tag in listOf("permission", "uses-permission")) {
            for (element in root.elements(tag)) {
                val value = element.android("name")
                val renamed = renameIdentifier(value, oldPackage, newPackage)
                if (renamed != value) element.setAndroid("name", renamed)
            }
        }

        val application = root.firstElement("application")
            ?: error("APK manifest has no <application>")
        // A compatible copy uses Android Auto's official parked-game Activity route. It must not
        // simultaneously advertise a Car App Library or legacy projection surface: Gearhead then
        // classifies it under that route, for which the Unknown sources switch explicitly does
        // not apply. Keep publisher services themselves but remove only their car discovery
        // declarations so any unrelated phone behavior stays intact.
        for (service in application.childElements("service")) {
            for (filter in service.childElements("intent-filter").toList()) {
                filter.childElements("action")
                    .filter { it.android("name") == CAR_APP_SERVICE_ACTION }
                    .forEach(filter::removeChild)
                filter.childElements("category")
                    .filter {
                        it.android("name") == PROJECTION_CATEGORY ||
                            it.android("name").startsWith("androidx.car.app.category.")
                    }
                    .forEach(filter::removeChild)
                if (filter.childElements("action").isEmpty()) {
                    service.removeChild(filter)
                }
            }
        }

        // A never-before-installed S25U control proved a maps/template clone is rejected because
        // the shell, not a trusted store, initiated its install. Parked games are the one general
        // Activity route covered by Android Auto's Unknown sources setting, so arbitrary phone
        // apps must be honest parked copies. This category makes an AAAD sideload discoverable.
        application.setAndroid("appCategory", "game")
        application.setAndroid("resizeableActivity", "true")
        val originalLabel = application.android("label")
        val patchedLabels = originalLabel
            .takeIf { it.startsWith("@string/") }
            ?.substringAfter('/')
            ?.let { patchStringResourceLabels(requireNotNull(file.parentFile), it) }
            ?: 0
        if (patchedLabels == 0) {
            application.setAndroid("label", "$displayLabel (Car)")
        }
        removeMeta(application, AA_METADATA)
        removeMeta(application, "androidx.car.app.minCarApiLevel")
        removeMeta(application, "distractionOptimized")

        var launchers = 0
        val activityTags = application.childElements("activity") +
            application.childElements("activity-alias")
        for (activity in activityTags) {
            for (filter in activity.childElements("intent-filter")) {
                val actions = filter.childElements("action").map { it.android("name") }
                val categories = filter.childElements("category").map { it.android("name") }
                if ("android.intent.action.MAIN" !in actions ||
                    "android.intent.category.LAUNCHER" !in categories
                ) continue
                launchers++
                activity.removeAttributeNS(ANDROID_NS, "screenOrientation")
                activity.setAndroid("resizeableActivity", "true")
                removeMeta(activity, "distractionOptimized")
                filter.childElements("category")
                    .filter {
                        it.android("name") == "androidx.car.app.category.NAVIGATION" ||
                            it.android("name") == "android.intent.category.APP_MAPS"
                    }
                    .forEach(filter::removeChild)
                val required = listOf(
                    "android.intent.category.DEFAULT",
                    "android.intent.category.CAR_LAUNCHER",
                )
                for (category in required) {
                    if (filter.childElements("category").none {
                            it.android("name") == category
                        }) {
                        filter.appendElement(document, "category").setAndroid("name", category)
                    }
                }
            }
        }
        check(launchers > 0) { "App has no launcher Activity to show in the car" }

        for (meta in root.elements("meta-data")) {
            if (meta.android("resource") == "@null" && meta.android("value").isBlank()) {
                meta.setAndroid("resource", "@mipmap/ic_launcher")
            }
        }

        writeXml(document, file)
    }

    private fun removeMeta(parent: Element, name: String) {
        parent.childElements("meta-data")
            .filter { it.android("name") == name }
            .forEach(parent::removeChild)
    }

    /**
     * Keep a publisher's resource-backed application label resource-backed. Android Auto's S25U
     * launcher accepted the proven host clone in this shape but rejected an otherwise identical
     * clone whose label was replaced by a manifest literal. Appending a text node preserves any
     * styled content and every translated label while making the copy distinguishable.
     */
    private fun patchStringResourceLabels(decoded: File, resourceName: String): Int {
        var changed = 0
        val resourceRoot = File(decoded, "resources")
        if (!resourceRoot.isDirectory) return 0
        resourceRoot.walkTopDown()
            .filter { file ->
                file.isFile && file.name == "strings.xml" &&
                    file.parentFile?.name?.startsWith("values") == true
            }
            .forEach { strings ->
                val document = readXml(strings)
                val labels = document.documentElement.childElements("string")
                    .filter { it.getAttribute("name") == resourceName }
                var dirty = false
                for (label in labels) {
                    if (!label.textContent.trimEnd().endsWith("(Car)")) {
                        label.appendChild(document.createTextNode(" (Car)"))
                        changed++
                        dirty = true
                    }
                }
                if (dirty) writeXml(document, strings)
            }
        return changed
    }

    private fun runEditor(stage: String, vararg arguments: String) {
        Logger.i(TAG, "APKEditor $stage")
        val exit = Main.execute(arguments)
        check(exit == 0) { "APKEditor $stage failed (exit $exit)" }
    }

    private fun readXml(file: File): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
        }
        return factory.newDocumentBuilder().parse(file)
    }

    private fun writeXml(document: Document, file: File) {
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "utf-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            runCatching {
                setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
            }
        }
        transformer.transform(DOMSource(document), StreamResult(file))
    }

    private fun renameIdentifier(value: String, old: String, new: String): String = when {
        value == old -> new
        value.startsWith("$old.") -> new + value.removePrefix(old)
        else -> value
    }

    private fun Element.android(name: String): String = getAttributeNS(ANDROID_NS, name)

    private fun Element.setAndroid(name: String, value: String): Element = apply {
        setAttributeNS(ANDROID_NS, "android:$name", value)
    }

    private fun Element.appendElement(document: Document, name: String): Element =
        document.createElement(name).also(::appendChild)

    private fun Element.childElements(name: String): List<Element> =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == name }

    private fun Element.firstElement(name: String): Element? =
        childElements(name).firstOrNull()

    private fun Element.elements(name: String): List<Element> =
        getElementsByTagName(name).let { nodes ->
            (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
        }

    private fun Element.allElements(): List<Element> =
        getElementsByTagName("*").let { nodes ->
            (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
        }

}
