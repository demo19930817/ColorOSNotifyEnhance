package com.demo1993.coloros.notify.enhance.rules

import android.content.Context
import com.demo1993.coloros.notify.enhance.core.ModuleInfo

/**
 * Download sources for the ANIP rule catalog.
 *
 * Mirrors the source list of `fankes/ColorOSNotifyIcon`: the catalog lives in a GitHub repository,
 * and every request may be routed through one of two public GitHub proxies. `raw.githubusercontent.com`
 * is not reachable on a lot of networks, which is what the proxies are for — the proxy is simply
 * prefixed onto the full source URL (`https://cdn.gh-proxy.org/https://raw.githubusercontent.com/...`),
 * exactly like the upstream `RemoteSource.UrlResolver` does it.
 */
enum class RuleSourceType(
    val storageValue: String,
    val proxyPrefix: String?,
) {
    GhProxy1("gh_proxy_1", "https://cdn.gh-proxy.org"),
    GhProxy2("gh_proxy_2", "https://ghfast.top"),
    GitHubDirect("github_direct", null),
    ;

    companion object {
        fun fromStorage(value: String?): RuleSourceType =
            entries.firstOrNull { it.storageValue == value } ?: GhProxy1
    }
}

/**
 * Where "更新规则" pulls from: the repository slug plus the route used to reach it.
 *
 * [repository] is an `owner/repository` slug so a fork or a mirror can be used as well; the branch is
 * always the upstream `main`, because every ANIP fork keeps that layout.
 */
data class RuleSourceConfig(
    val type: RuleSourceType = RuleSourceType.GhProxy1,
    val repository: String = ModuleInfo.ANIP_REPO,
) {
    val branch: String get() = ModuleInfo.ANIP_BRANCH

    val isValidRepository: Boolean get() = REPOSITORY_PATTERN.matches(repository)

    /** Source URL of [path] inside the repository, before any proxy is applied. */
    fun rawUrl(path: String): String = "https://raw.githubusercontent.com/$repository/$branch/$path"

    /** jsDelivr mirrors the branch and needs no proxy, so it doubles as the last resort. */
    fun jsDelivrUrl(path: String): String = "https://cdn.jsdelivr.net/gh/$repository@$branch/$path"

    fun resolve(type: RuleSourceType, path: String): String {
        val url = rawUrl(path)
        return type.proxyPrefix?.let { "$it/$url" } ?: url
    }

    /** User-facing name of one route, also written into the diagnostics log. */
    fun labelOf(type: RuleSourceType): String = when (type) {
        RuleSourceType.GhProxy1 -> "加速节点 1"
        RuleSourceType.GhProxy2 -> "加速节点 2"
        RuleSourceType.GitHubDirect -> "GitHub 直连"
    }

    companion object {
        private val REPOSITORY_PATTERN = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")
        const val JSDELIVR_LABEL = "jsDelivr"

        fun normalizeRepository(raw: String): String? {
            val trimmed = raw.trim().removePrefix("https://github.com/").removeSuffix("/")
            return trimmed.takeIf { REPOSITORY_PATTERN.matches(it) }
        }
    }
}

/**
 * Attempt order for a single catalog resource.
 *
 * The configured route is tried first, then the other two routes in a fixed order, and jsDelivr last.
 * Once one route succeeds it becomes the head of the list for every later request in the same sync,
 * so a dead proxy costs one timeout instead of one per icon.
 */
internal class RuleSourcePlan(private val config: RuleSourceConfig) {

    private data class Route(val label: String, val build: (String) -> String)

    private val routes: List<Route> = buildList {
        val ordered = listOf(config.type) + RuleSourceType.entries.filter { it != config.type }
        ordered.forEach { type -> add(Route(config.labelOf(type)) { path -> config.resolve(type, path) }) }
        add(Route(RuleSourceConfig.JSDELIVR_LABEL) { path -> config.jsDelivrUrl(path) })
    }

    @Volatile
    var preferredRoute: Int = 0
        private set

    /** Route that produced the last successful download, for the diagnostics log. */
    val winningLabel: String get() = routes[preferredRoute.coerceIn(0, routes.lastIndex)].label

    val repository: String get() = config.repository

    fun <T> fetch(path: String, reader: (String) -> T): T {
        val head = preferredRoute.coerceIn(0, routes.lastIndex)
        var firstFailure: Exception? = null
        val order = listOf(head) + routes.indices.filter { it != head }
        for (index in order) {
            try {
                val value = reader(routes[index].build(path))
                preferredRoute = index
                return value
            } catch (exception: Exception) {
                val previous = firstFailure
                if (previous == null) {
                    firstFailure = exception
                } else {
                    previous.addSuppressed(exception)
                }
            }
        }
        throw firstFailure ?: IllegalStateException("No route available for $path")
    }
}

private const val SOURCE_PREFS = "coloros_notify_enhance_rule_source_prefs"
private const val KEY_SOURCE_TYPE = "rule_source_type"
private const val KEY_REPOSITORY = "rule_source_repository"

fun readRuleSourceConfig(context: Context): RuleSourceConfig {
    val prefs = context.getSharedPreferences(SOURCE_PREFS, Context.MODE_PRIVATE)
    val repository = prefs.getString(KEY_REPOSITORY, null)
        ?.takeIf { RuleSourceConfig(it).isValidRepository }
        ?: ModuleInfo.ANIP_REPO
    return RuleSourceConfig(
        type = RuleSourceType.fromStorage(prefs.getString(KEY_SOURCE_TYPE, null)),
        repository = repository,
    )
}

fun writeRuleSourceConfig(context: Context, config: RuleSourceConfig) {
    context.getSharedPreferences(SOURCE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_SOURCE_TYPE, config.type.storageValue)
        .putString(KEY_REPOSITORY, config.repository)
        .apply()
}
