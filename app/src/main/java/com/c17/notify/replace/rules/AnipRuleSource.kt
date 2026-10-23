package com.c17.notify.replace.rules

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal object AnipRuleSource {

    /**
     * The three catalogs of the ANIP repository. [manifestSegment] is the index file, [resSegment]
     * the directory holding one PNG per package.
     */
    private enum class CatalogKind(
        val manifestSegment: String,
        val resSegment: String,
    ) {
        App("icons/app/manifest.json", "icons/app/res"),
        Game("icons/game/manifest.json", "icons/game/res"),
        ColorOs("icons/system/coloros/manifest.json", "icons/system/coloros/res"),
    }

    fun sync(httpClient: OkHttpClient, plan: RuleSourcePlan): String {
        val colorOsManifest = downloadManifest(
            httpClient = httpClient,
            plan = plan,
            path = CatalogKind.ColorOs.manifestSegment,
            source = "ColorOS",
        )
        val gameManifest = downloadManifest(
            httpClient = httpClient,
            plan = plan,
            path = CatalogKind.Game.manifestSegment,
            source = "games",
        )
        val appManifest = downloadManifest(
            httpClient = httpClient,
            plan = plan,
            path = CatalogKind.App.manifestSegment,
            source = "applications",
        )
        val entries = mergeManifests(colorOsManifest, gameManifest, appManifest)
        val inputs = downloadRuleInputs(httpClient, plan, entries)
        check(inputs.isNotEmpty()) { "No rule entries could be downloaded from ANIP" }
        return inputsToJson(inputs)
    }

    private fun downloadManifest(
        httpClient: OkHttpClient,
        plan: RuleSourcePlan,
        path: String,
        source: String,
    ): JSONObject = try {
        plan.fetch(path) { url ->
            JSONObject(downloadText(httpClient, url, RulePayloadIO.MAX_PAYLOAD_BYTES))
        }
    } catch (exception: Exception) {
        throw RuleRepository.SyncFailure.Download(source, exception)
    }

    private fun mergeManifests(
        colorOsManifest: JSONObject,
        gameManifest: JSONObject,
        appManifest: JSONObject,
    ): Map<String, CatalogEntry> {
        val merged = linkedMapOf<String, CatalogEntry>()
        fun putAll(manifest: JSONObject, kind: CatalogKind) {
            manifest.keys().forEach { packageName ->
                merged[packageName] = CatalogEntry(
                    packageName = packageName,
                    kind = kind,
                    raw = manifest.getJSONObject(packageName),
                )
            }
        }
        // Later sources override earlier ones for the same package.
        putAll(colorOsManifest, CatalogKind.ColorOs)
        putAll(gameManifest, CatalogKind.Game)
        putAll(appManifest, CatalogKind.App)
        return merged
    }

    private fun downloadRuleInputs(
        httpClient: OkHttpClient,
        plan: RuleSourcePlan,
        entries: Map<String, CatalogEntry>,
    ): List<RuleInput> {
        val executor = Executors.newFixedThreadPool(DOWNLOAD_PARALLELISM)
        try {
            val futures = ArrayList<Future<RuleInput?>>(entries.size)
            entries.values.forEach { entry ->
                futures += executor.submit<RuleInput?> {
                    buildRuleInput(httpClient, plan, entries, entry)
                }
            }
            return futures.mapNotNull { future ->
                try {
                    future.get(ICON_DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    null
                }
            }
        } finally {
            executor.shutdown()
        }
    }

    private fun buildRuleInput(
        httpClient: OkHttpClient,
        plan: RuleSourcePlan,
        entries: Map<String, CatalogEntry>,
        entry: CatalogEntry,
    ): RuleInput? {
        val iconPackage = resolveIconPackage(entries, entry.packageName) ?: return null
        val iconKind = entries[iconPackage]?.kind ?: entry.kind
        val iconBytes = downloadIconBytes(httpClient, plan, iconKind, iconPackage) ?: return null
        val sourceEntry = entries[iconPackage]?.raw ?: entry.raw
        val label = readLabel(entry.raw).ifBlank { readLabel(sourceEntry) }.ifBlank { entry.packageName }
        val color = readColor(entry.raw).ifBlank { readColor(sourceEntry) }
        val contributors = entry.raw.optString("contributors")
            .ifBlank { sourceEntry.optString("contributors") }
        return RuleInput(
            appName = label,
            packageName = entry.packageName,
            iconBase64 = Base64.getEncoder().encodeToString(iconBytes),
            iconColor = normalizeColor(color),
            contributorName = contributors,
            enabledByDefault = true,
            enabledAllByDefault = entry.raw.optBoolean("overlay", sourceEntry.optBoolean("overlay", false)),
        )
    }

    private fun resolveIconPackage(
        entries: Map<String, CatalogEntry>,
        packageName: String,
    ): String? {
        val visited = linkedSetOf<String>()
        var current = packageName
        while (visited.add(current)) {
            val entry = entries[current] ?: return null
            val target = entry.raw.optString("target").trim()
            if (target.isEmpty()) return current
            if (!RuleCatalogParser.isValidPackageName(target)) return null
            current = target
        }
        return null
    }

    private fun downloadIconBytes(
        httpClient: OkHttpClient,
        plan: RuleSourcePlan,
        kind: CatalogKind,
        packageName: String,
    ): ByteArray? = try {
        plan.fetch("${kind.resSegment}/$packageName.png") { url ->
            downloadBytes(httpClient, url, MAX_ICON_BYTES)
        }
    } catch (_: Exception) {
        // One missing icon must not fail the whole catalog; the app simply keeps its own artwork.
        null
    }

    private fun downloadText(
        httpClient: OkHttpClient,
        url: String,
        maxBytes: Int,
    ): String {
        val bytes = downloadBytes(httpClient, url, maxBytes)
        return bytes.toString(Charsets.UTF_8).trim()
    }

    private fun downloadBytes(
        httpClient: OkHttpClient,
        url: String,
        maxBytes: Int,
    ): ByteArray {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} for $url" }
            val contentLength = response.body.contentLength()
            require(contentLength < 0L || contentLength <= maxBytes) {
                "Response exceeds $maxBytes bytes for $url"
            }
            return response.body.byteStream().use { input ->
                RulePayloadIO.readBytes(input, maxBytes)
            }
        }
    }

    private fun readLabel(raw: JSONObject): String {
        when (val label = raw.opt("label")) {
            is String -> return label.trim()
            is JSONObject -> {
                label.optString("zh-CN").trim().takeIf(String::isNotEmpty)?.let { return it }
                label.optString("en").trim().takeIf(String::isNotEmpty)?.let { return it }
                val iterator = label.keys()
                while (iterator.hasNext()) {
                    val value = label.optString(iterator.next()).trim()
                    if (value.isNotEmpty()) return value
                }
            }
        }
        return ""
    }

    private fun readColor(raw: JSONObject): String = raw.optString("color").trim()

    private fun normalizeColor(raw: String): String {
        if (raw.isBlank()) return ""
        return if (raw.startsWith('#')) raw else "#$raw"
    }

    private fun inputsToJson(inputs: List<RuleInput>): String {
        val array = JSONArray()
        inputs.sortedBy { it.packageName }.forEach { input ->
            array.put(
                JSONObject()
                    .put("appName", input.appName)
                    .put("packageName", input.packageName)
                    .put("iconBitmap", input.iconBase64)
                    .put("iconColor", input.iconColor)
                    .put("contributorName", input.contributorName)
                    .put("isEnabled", input.enabledByDefault)
                    .put("isEnabledAll", input.enabledAllByDefault),
            )
        }
        return array.toString()
    }

    private data class CatalogEntry(
        val packageName: String,
        val kind: CatalogKind,
        val raw: JSONObject,
    )

    private const val DOWNLOAD_PARALLELISM = 8
    private const val ICON_DOWNLOAD_TIMEOUT_SECONDS = 45L
    private const val MAX_ICON_BYTES = 256 * 1024
}
