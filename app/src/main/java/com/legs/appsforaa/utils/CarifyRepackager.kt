package com.legs.appsforaa.utils

import android.content.Context
import com.legs.appsforaa.BuildConfig
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
import java.util.Locale
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
 * the clone is signed with [CarifySigner] and installed with Play attribution through Shizuku.
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
        const val BRIDGE_SERVICE =
            "com.legs.appsforaa.carify.CarifyCarAppService"
        const val BRIDGE_CLASS_MARKER = "Landroidx/car/app/CarAppService;"
    }

    suspend fun convert(
        app: InstalledApp,
        installMode: InstallMode = InstallMode.SHIZUKU,
        onProgress: (Stage) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
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
            val patch = patchManifest(
                File(decoded, "AndroidManifest.xml"),
                app.packageName,
                clonePackage,
                app.label,
            )
            addDescriptor(decoded, patch.carUses)
            if (patch.needsBridge) injectBridge(decoded)
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

    private data class ManifestPatch(
        val carUses: String,
        val needsBridge: Boolean,
    )

    private fun patchManifest(
        file: File,
        oldPackage: String,
        newPackage: String,
        displayLabel: String,
    ): ManifestPatch {
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
        var hasProjectionService = false
        var hasCarAppService = false
        for (service in application.childElements("service")) {
            for (filter in service.childElements("intent-filter")) {
                val categories = filter.childElements("category").map { it.android("name") }
                val actions = filter.childElements("action").map { it.android("name") }
                if (PROJECTION_CATEGORY in categories) hasProjectionService = true
                if (CAR_APP_SERVICE_ACTION in actions) hasCarAppService = true
            }
        }

        val needsBridge = !hasProjectionService && !hasCarAppService
        val carUses = when {
            hasProjectionService -> "projection"
            else -> "template"
        }

        if (needsBridge) {
            addUsesPermission(document, root, application, "androidx.car.app.ACCESS_SURFACE")
            addUsesPermission(document, root, application, "androidx.car.app.MAP_TEMPLATES")
            addUsesPermission(document, root, application, "androidx.car.app.NAVIGATION_TEMPLATES")

            val queries = root.firstElement("queries")
                ?: document.createElement("queries").also { root.insertBefore(it, application) }
            if (queries.childElements("provider").none {
                    it.android("authorities") == "androidx.car.app.connection"
                }) {
                queries.appendElement(document, "provider").apply {
                    setAndroid("name", "androidx.car.app.connection.provider")
                    setAndroid("authorities", "androidx.car.app.connection")
                }
            }

            application.appendElement(document, "activity").apply {
                setAndroid("name", "androidx.car.app.CarAppPermissionActivity")
                setAndroid("exported", "false")
                setAndroid("theme", "@android:style/Theme.Translucent.NoTitleBar")
            }
            application.appendElement(document, "receiver").apply {
                setAndroid(
                    "name",
                    "androidx.car.app.notification.CarAppNotificationBroadcastReceiver",
                )
                setAndroid("exported", "false")
            }
            application.appendElement(document, "service").apply {
                setAndroid("name", BRIDGE_SERVICE)
                setAndroid("exported", "true")
                appendElement(document, "intent-filter").apply {
                    appendElement(document, "action")
                        .setAndroid("name", CAR_APP_SERVICE_ACTION)
                    appendElement(document, "category")
                        .setAndroid("name", "androidx.car.app.category.NAVIGATION")
                }
            }
            setMeta(document, application, "androidx.car.app.minCarApiLevel", value = "7")
        }

        // Measured on the S25U: this is the custom-app discovery discriminator. It is applied to
        // every rewritten clone, including clones that preserve a publisher's own car service.
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
        setMeta(document, application, AA_METADATA, resource = "@xml/automotive_app_desc")
        setMeta(document, application, "distractionOptimized", value = "true")

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
                setMeta(document, activity, "distractionOptimized", value = "true")
                val required = listOf(
                    "android.intent.category.DEFAULT",
                    "android.intent.category.CAR_LAUNCHER",
                    "androidx.car.app.category.NAVIGATION",
                    "android.intent.category.APP_MAPS",
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
        return ManifestPatch(carUses, needsBridge)
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

    private fun addDescriptor(decoded: File, carUses: String) {
        val packageJson = File(decoded, "resources").walkTopDown()
            .firstOrNull { it.isFile && it.name == "package.json" }
            ?: error("Decoded APK has no resource package")
        val res = File(packageJson.parentFile, "res")
        val xml = File(res, "xml").apply { mkdirs() }
        File(xml, "automotive_app_desc.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<automotiveApp>\n    <uses name=\"$carUses\"/>\n</automotiveApp>\n"
        )

        val publicXml = File(res, "values/public.xml")
        check(publicXml.isFile) { "Decoded APK has no public.xml" }
        val document = readXml(publicXml)
        val root = document.documentElement
        val entries = root.childElements("public")
        if (entries.none { it.getAttribute("name") == "automotive_app_desc" }) {
            val allIds = entries.mapNotNull { it.getAttribute("id").parseResourceId() }
            val xmlIds = entries
                .filter { it.getAttribute("type") == "xml" }
                .mapNotNull { it.getAttribute("id").parseResourceId() }
            val newId = if (xmlIds.isNotEmpty()) {
                xmlIds.max() + 1
            } else {
                val highestType = allIds.maxOfOrNull { it ushr 16 } ?: 0x12
                (highestType + 1) shl 16
            }
            root.appendElement(document, "public").apply {
                setAttribute("id", String.format(Locale.US, "0x%08x", newId))
                setAttribute("type", "xml")
                setAttribute("name", "automotive_app_desc")
            }
            writeXml(document, publicXml)
        }
    }

    private fun injectBridge(decoded: File) {
        val dexDir = File(decoded, "dex")
        val dexFiles = dexDir.listFiles()
            ?.filter { it.name.matches(Regex("classes(?:\\d+)?\\.dex")) }
            .orEmpty()
        check(dexFiles.isNotEmpty()) { "Decoded APK has no DEX files" }
        if (dexFiles.any { it.containsAscii(BRIDGE_CLASS_MARKER) }) {
            error("APK contains an undeclared Car App runtime; refusing a duplicate")
        }

        val next = dexFiles.maxOf { file ->
            if (file.name == "classes.dex") 1
            else file.name.removePrefix("classes").removeSuffix(".dex").toInt()
        } + 1
        context.assets.open("carify/bridge.dex").use { input ->
            File(dexDir, "classes$next.dex").outputStream().use(input::copyTo)
        }
    }

    private fun runEditor(stage: String, vararg arguments: String) {
        Logger.i(TAG, "APKEditor $stage")
        val exit = Main.execute(arguments)
        check(exit == 0) { "APKEditor $stage failed (exit $exit)" }
    }

    private fun addUsesPermission(
        document: Document,
        root: Element,
        application: Element,
        name: String,
    ) {
        if (root.childElements("uses-permission").any { it.android("name") == name }) return
        document.createElement("uses-permission").also {
            it.setAndroid("name", name)
            root.insertBefore(it, application)
        }
    }

    private fun setMeta(
        document: Document,
        parent: Element,
        name: String,
        value: String? = null,
        resource: String? = null,
    ) {
        parent.childElements("meta-data")
            .filter { it.android("name") == name }
            .forEach(parent::removeChild)
        parent.appendElement(document, "meta-data").apply {
            setAndroid("name", name)
            value?.let { setAndroid("value", it) }
            resource?.let { setAndroid("resource", it) }
        }
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

    private fun String.parseResourceId(): Long? =
        removePrefix("0x").takeIf { it.matches(Regex("[0-9a-fA-F]{8}")) }?.toLong(16)

    private fun File.containsAscii(value: String): Boolean {
        val target = value.toByteArray(Charsets.US_ASCII)
        inputStream().buffered().use { input ->
            var matched = 0
            while (true) {
                val next = input.read()
                if (next < 0) return false
                matched = if (next.toByte() == target[matched]) matched + 1
                else if (next.toByte() == target[0]) 1 else 0
                if (matched == target.size) return true
            }
        }
    }
}
