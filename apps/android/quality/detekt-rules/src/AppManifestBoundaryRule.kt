package com.lomo.detektrules

import dev.detekt.api.Config
import org.jetbrains.kotlin.psi.KtFile
import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal class AppManifestBoundaryRule(config: Config) : LomoBaseRule(
    config,
    "The app manifest cannot name data-layer components; module dependency scopes are owned by Rust architecture tests.",
) {
    private val checkedManifests = mutableSetOf<Path>()

    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        if (!file.path().contains("/app/src/")) return
        val root = file.moduleRoot() ?: return
        val manifest = Path.of(root, "src", "AndroidManifest.xml")
        if (!Files.isRegularFile(manifest) || !checkedManifests.add(manifest)) return
        try {
            dataComponents(manifest).forEach { component ->
                reportFile(file, "app AndroidManifest.xml must not directly name data-layer component: $component")
            }
        } catch (error: SAXException) {
            reportFile(file, "Invalid app AndroidManifest.xml: ${error.message}")
        } catch (error: IOException) {
            reportFile(file, "Cannot read app AndroidManifest.xml: ${error.message}")
        }
    }

    private fun dataComponents(manifest: Path): Set<String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val document = factory.newDocumentBuilder().parse(manifest.toFile())
        val elements = document.getElementsByTagName("*")
        return (0 until elements.length).map { index ->
            (elements.item(index) as Element).getAttributeNS("http://schemas.android.com/apk/res/android", "name")
        }.filter { it.startsWith("com.lomo.data.") }.toSet()
    }
}
