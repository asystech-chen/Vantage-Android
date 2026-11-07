/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings.doh

import androidx.annotation.VisibleForTesting
import mozilla.components.concept.engine.Engine
import org.mozilla.fenix.utils.Settings
import org.ironfoxoss.core.IFDohProviders

internal interface DohSettingsProvider {
    fun getProtectionLevels(): List<ProtectionLevel>

    fun getDefaultProviders(): List<Provider>

    fun getSelectedProtectionLevel(): ProtectionLevel

    fun getSelectedProvider(): Provider?

    fun getExceptions(): List<String>

    fun setProtectionLevel(protectionLevel: ProtectionLevel, provider: Provider?)

    fun setCustomProvider(url: String)

    fun setExceptions(exceptions: List<String>)
}

internal class DefaultDohSettingsProvider(
    val engine: Engine,
    val settings: Settings,
) : DohSettingsProvider {
    override fun getProtectionLevels(): List<ProtectionLevel> {
        return listOf(
            ProtectionLevel.Default,
            ProtectionLevel.Increased,
            ProtectionLevel.Max,
            ProtectionLevel.Off,
        )
    }

    // Bug 1946867 - Load DoH Provider List from Gecko instead of hardcoding
    private val dohDefaultProviderUrl = settings.dohDefaultProviderUrl

    private val cloudflare =
        Provider.BuiltIn(
            url = cloudflareUri,
            name = IFDohProviders.cloudflareName,
            default = dohDefaultProviderUrl == cloudflareUri,
        )
    // Vantage: AliDNS is the default provider.
    private val alidns =
        Provider.BuiltIn(
            url = alidnsUri,
            name = alidnsName,
            default = dohDefaultProviderUrl.isBlank() || dohDefaultProviderUrl == alidnsUri,
        )
    private val quad9 =
        Provider.BuiltIn(
            url = IFDohProviders.quad9Uri,
            name = IFDohProviders.quad9Name,
            default = dohDefaultProviderUrl == IFDohProviders.quad9Uri,
        )
    private val cloudflareMalware =
        Provider.BuiltIn(
            url = IFDohProviders.cloudflareMalwareUri,
            name = IFDohProviders.cloudflareMalwareName,
            default = dohDefaultProviderUrl == IFDohProviders.cloudflareMalwareUri,
        )
    private val dns4EuAdBlocking =
        Provider.BuiltIn(
            url = IFDohProviders.dns4EuAdBlockingUri,
            name = IFDohProviders.dns4EuAdBlockingName,
            default = dohDefaultProviderUrl == IFDohProviders.dns4EuAdBlockingUri,
        )
    private val dns4EuProtective =
        Provider.BuiltIn(
            url = IFDohProviders.dns4EuProtectiveUri,
            name = IFDohProviders.dns4EuProtectiveName,
            default = dohDefaultProviderUrl == IFDohProviders.dns4EuProtectiveUri,
        )
    private val dns4EuUnfiltered =
        Provider.BuiltIn(
            url = IFDohProviders.dns4EuUnfilteredUri,
            name = IFDohProviders.dns4EuUnfilteredName,
            default = dohDefaultProviderUrl == IFDohProviders.dns4EuUnfilteredUri,
        )
    private val dnsBunker =
        Provider.BuiltIn(
            url = IFDohProviders.dnsBunkerUri,
            name = IFDohProviders.dnsBunkerName,
            default = dohDefaultProviderUrl == IFDohProviders.dnsBunkerUri,
        )
    private val dnsForgeBlank =
        Provider.BuiltIn(
            url = IFDohProviders.dnsForgeBlankUri,
            name = IFDohProviders.dnsForgeBlankName,
            default = dohDefaultProviderUrl == IFDohProviders.dnsForgeBlankUri,
        )
    private val dnsForgeHard =
        Provider.BuiltIn(
            url = IFDohProviders.dnsForgeHardUri,
            name = IFDohProviders.dnsForgeHardName,
            default = dohDefaultProviderUrl == IFDohProviders.dnsForgeHardUri,
        )
    private val dnsForgeNormal =
        Provider.BuiltIn(
            url = IFDohProviders.dnsForgeNormalUri,
            name = IFDohProviders.dnsForgeNormalName,
            default = dohDefaultProviderUrl == IFDohProviders.dnsForgeNormalUri,
        )
    private val providerUrl = settings.dohProviderUrl
    private val custom =
        Provider.Custom(
            url =
                if (providerUrl != alidnsUri && providerUrl != cloudflareUri && providerUrl != IFDohProviders.quad9Uri &&
         providerUrl != IFDohProviders.cloudflareMalwareUri && providerUrl != IFDohProviders.dns4EuAdBlockingUri &&
         providerUrl != IFDohProviders.dns4EuProtectiveUri && providerUrl != IFDohProviders.dns4EuUnfilteredUri &&
         providerUrl != IFDohProviders.dnsBunkerUri && providerUrl != IFDohProviders.dnsForgeBlankUri &&
         providerUrl != IFDohProviders.dnsForgeHardUri && providerUrl != IFDohProviders.dnsForgeNormalUri) {
                    providerUrl
                } else {
                    ""
                }
        )

    override fun getDefaultProviders(): List<Provider> =
        listOf(
            alidns,
            quad9,
            cloudflareMalware,
            cloudflare,
            dns4EuAdBlocking,
            dns4EuProtective,
            dns4EuUnfiltered,
            dnsBunker,
            dnsForgeNormal,
            dnsForgeHard,
            dnsForgeBlank,
            custom,
        )

    override fun getSelectedProtectionLevel(): ProtectionLevel {
        return when (settings.getDohSettingsMode()) {
            Engine.DohSettingsMode.DEFAULT -> ProtectionLevel.Default
            Engine.DohSettingsMode.INCREASED -> ProtectionLevel.Increased
            Engine.DohSettingsMode.MAX -> ProtectionLevel.Max
            Engine.DohSettingsMode.OFF -> ProtectionLevel.Off
        }
    }

    override fun getSelectedProvider(): Provider? {
        return when (settings.getDohSettingsMode()) {
            Engine.DohSettingsMode.OFF,
            Engine.DohSettingsMode.DEFAULT -> {
                null
            }

            else -> {
                when (settings.dohProviderUrl) {
                    alidnsUri -> alidns
                    cloudflareUri -> cloudflare
                    IFDohProviders.quad9Uri -> quad9
                    IFDohProviders.cloudflareMalwareUri -> cloudflareMalware
                    IFDohProviders.dns4EuAdBlockingUri -> dns4EuAdBlocking
                    IFDohProviders.dns4EuProtectiveUri -> dns4EuProtective
                    IFDohProviders.dns4EuUnfilteredUri -> dns4EuUnfiltered
                    IFDohProviders.dnsBunkerUri -> dnsBunker
                    IFDohProviders.dnsForgeBlankUri -> dnsForgeBlank
                    IFDohProviders.dnsForgeHardUri -> dnsForgeHard
                    IFDohProviders.dnsForgeNormalUri -> dnsForgeNormal
                    "" -> getDefaultProviders().first()
                    else -> custom
                }
            }
        }
    }

    override fun getExceptions(): List<String> {
        return settings.dohExceptionsList.toList()
    }

    override fun setProtectionLevel(protectionLevel: ProtectionLevel, provider: Provider?) {
        if (protectionLevel is ProtectionLevel.Increased || protectionLevel is ProtectionLevel.Max) {
            requireNotNull(provider) { "Provider must not be null for Increased/Max protection level" }
            settings.dohProviderUrl = provider.url
            engine.settings.dohProviderUrl = provider.url
        }

        val newMode = protectionLevel.toDohSettingsMode()
        // Update the app layer
        settings.setDohSettingsMode(newMode)
        engine.settings.dohSettingsMode = newMode
    }

    override fun setCustomProvider(url: String) {
        // Update the app layer
        settings.dohProviderUrl = url
        // validate the url, maybe throw some "known expectations" that we can handle in the middleware
        engine.settings.dohProviderUrl = url
    }

    override fun setExceptions(exceptions: List<String>) {
        // Update the app layer
        settings.dohExceptionsList = exceptions.toSet()
        engine.settings.dohExceptionsList = exceptions
    }

    companion object {
        @VisibleForTesting val alidnsUri = "https://dns.alidns.com/dns-query"
        private const val alidnsName = "阿里 DNS 🇨🇳"

        @VisibleForTesting val cloudflareUri = "https://mozilla.cloudflare-dns.com/dns-query"

        @VisibleForTesting val nextDnsUri = "https://firefox.dns.nextdns.io/"
    }
}
