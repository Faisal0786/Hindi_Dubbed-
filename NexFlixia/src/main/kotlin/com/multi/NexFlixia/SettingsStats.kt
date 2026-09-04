package com.hindi.providers

internal object SettingsStats {

    /** Total built-in providers */
    fun providerCount(): Int =
        SourceRegistry.builtInProviders.size

    /** Currently enabled providers (including enabled addons) */
    fun enabledProviderCount(): Int =
        Settings.activeProviderOrder.size

    /** Installed Stremio addons */
    fun addonCount(): Int =
        Settings.getStremioAddons().size

    /** Scraping concurrency */
    fun concurrency(): Int =
        Settings.getConcurrency()

    /** Saved authentication tokens */
    fun tokenCount(): Int {
        var count = 0

        if (!Settings.getShowboxToken().isNullOrBlank()) count++
        if (!Settings.getGramCinemaToken().isNullOrBlank()) count++
        if (!Settings.getWyzieSubsKey().isNullOrBlank()) count++

        return count
    }

    /** Saved Cloudflare cookies */
    fun cookieCount(): Int =
        Settings.getCloudflareBypassDomains()
            .count { Settings.hasSavedCookieForDomain(it.domain) }
}