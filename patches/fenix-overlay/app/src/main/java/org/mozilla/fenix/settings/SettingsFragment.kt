/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings

import android.Manifest.permission.ACCESS_LOCAL_NETWORK
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavDirections
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.lang.ref.WeakReference
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.sync.AccountObserver
import mozilla.components.concept.sync.AuthType
import mozilla.components.concept.sync.OAuthAccount
import mozilla.components.concept.sync.Profile
import mozilla.components.feature.addons.ui.AddonFilePicker
import mozilla.components.feature.ipprotection.store.IPProtectionStore
import mozilla.components.feature.ipprotection.store.state.isEligible
import mozilla.components.service.fxrelay.eligibility.Eligible
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.ktx.android.view.showKeyboard
import mozilla.components.support.utils.BuildManufacturerChecker
import mozilla.components.support.utils.DateTimeProvider
import mozilla.components.support.utils.DefaultDateTimeProvider
import mozilla.components.support.utils.ext.navigateToDefaultBrowserAppsSettings
import mozilla.components.ui.icons.R as iconsR
import mozilla.components.ui.widgets.withCenterAlignedButtons
import mozilla.telemetry.glean.private.NoExtras
import org.mozilla.fenix.Config
import org.mozilla.fenix.FeatureFlags
import org.mozilla.fenix.GleanMetrics.Addons
import org.mozilla.fenix.GleanMetrics.Events
import org.mozilla.fenix.GleanMetrics.Settings as SettingsMetrics
import org.mozilla.fenix.GleanMetrics.SettingsSearch
import org.mozilla.fenix.GleanMetrics.TrackingProtection
import org.mozilla.fenix.GleanMetrics.Translations
import org.mozilla.fenix.GleanMetrics.Vpn
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.components.Components
import org.mozilla.fenix.components.accounts.FenixFxAEntryPoint
import org.mozilla.fenix.databinding.AmoCollectionOverrideDialogBinding
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.application
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.getPreferenceKey
import org.mozilla.fenix.ext.navigateToAppDetailsSettings
import org.mozilla.fenix.ext.navigateToNotificationsSettings
import org.mozilla.fenix.ext.openInNewTab
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.ext.showToolbarWithIconButton
import org.mozilla.fenix.home.maybeNavigateToSystemSetToDefaultAction
import org.mozilla.fenix.home.maybeRequestDefaultBrowserPrompt
import org.mozilla.fenix.nimbus.FxNimbus
import org.mozilla.fenix.perf.ProfilerViewModel
import org.mozilla.fenix.perf.ProfilerViewModelFactory
import org.mozilla.fenix.settings.account.AccountUiView
import org.mozilla.fenix.snackbar.FenixSnackbarDelegate
import org.mozilla.fenix.snackbar.SnackbarBinding
import org.mozilla.fenix.utils.Settings
import org.ironfoxoss.ironfox.utils.FenixSettingsVisibilityManager

/** Main settings screen. */
@Suppress("LargeClass", "TooManyFunctions")
class SettingsFragment : PreferenceFragmentCompat(), SystemInsetsPaddedFragment {

    private val args by navArgs<SettingsFragmentArgs>()
    private lateinit var accountUiView: AccountUiView
    private lateinit var addonFilePicker: AddonFilePicker
    private lateinit var components: Components
    private val profilerViewModel: ProfilerViewModel by activityViewModels {
        ProfilerViewModelFactory(requireActivity().application)
    }
    private val snackbarBinding = ViewBoundFeatureWrapper<SnackbarBinding>()

    private val requestLocalNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            localNetworkPermissionRequestAttempted = true
            setupLocalNetworkPreference(isGranted)
        }

    private var localNetworkPermissionRequestAttempted = false

    // Vantage: inline settings search state.
    private val originalPreferenceVisibility = mutableMapOf<Preference, Boolean>()
    private var filteringSettings = false

    private val dateTimeProvider: DateTimeProvider by lazy { DefaultDateTimeProvider() }

    @VisibleForTesting
    internal val accountObserver =
        object : AccountObserver {
            private fun updateAccountUi(profile: Profile? = null) {
                val context = context ?: return
                lifecycleScope.launch {
                    accountUiView.updateAccountUIState(
                        context = context,
                        profile = profile ?: context.components.backgroundServices.accountManager.accountProfile(),
                    )
                }
            }

            override fun onAuthenticated(account: OAuthAccount, authType: AuthType) = updateAccountUi()

            override fun onLoggedOut() = updateAccountUi()

            override fun onProfileUpdated(profile: Profile) = updateAccountUi(profile)

            override fun onAuthenticationProblems() = updateAccountUi()
        }

    // A flag used to track if we're going through the onCreate->onStart->onResume lifecycle chain.
    // If it's set to `true`, code in `onResume` can assume that `onCreate` executed a moment prior.
    // This flag is set to `false` at the end of `onResume`.
    private var creatingFragment = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        components = requireContext().components

        accountUiView =
            AccountUiView(
                fragment = this,
                scope = lifecycleScope,
                accountManager = requireComponents.backgroundServices.accountManager,
                httpClient = requireComponents.core.client,
            )

        addonFilePicker = AddonFilePicker(requireContext(), requireComponents.addonManager)
        addonFilePicker.registerForResults(this)

        // It's important to update the account UI state in onCreate since that ensures we'll never
        // display an incorrect state in the UI. We take care to not also call it as part of onResume
        // if it was just called here (via the 'creatingFragment' flag).
        // For example, if user is signed-in, and we don't perform this call in onCreate, we'll briefly
        // display a "Sign In" preference, which will then get replaced by the correct account information
        // once this call is ran in onResume shortly after.
        if (requireComponents.settings.ironfox.fxaEnabled) {
            accountUiView.updateAccountUIState(
                requireContext(),
                requireComponents.backgroundServices.accountManager.accountProfile(),
            )
        }

        val booleanPreferenceTelemetryAllowList =
            with(requireContext()) {
                listOf(
                    getString(R.string.pref_key_show_search_suggestions),
                    getString(R.string.pref_key_remote_debugging),
                    getString(R.string.pref_key_telemetry),
                    getString(R.string.pref_key_marketing_telemetry),
                    getString(R.string.pref_key_learn_about_marketing_telemetry),
                    getString(R.string.pref_key_tracking_protection),
                    getString(R.string.pref_key_search_bookmarks),
                    getString(R.string.pref_key_search_browsing_history),
                    getString(R.string.pref_key_show_clipboard_suggestions),
                    getString(R.string.pref_key_open_links_in_a_private_tab),
                    getString(R.string.pref_key_sync_logins),
                    getString(R.string.pref_key_sync_bookmarks),
                    getString(R.string.pref_key_sync_history),
                    getString(R.string.pref_key_show_voice_search),
                    getString(R.string.pref_key_show_search_suggestions_in_private),
                    getString(R.string.pref_key_show_trending_search_suggestions),
                    getString(R.string.pref_key_show_recent_search_suggestions),
                )
            }

        preferenceManager?.sharedPreferences?.registerOnSharedPreferenceChangeListener(this) { sharedPreferences, key ->
            try {
                if (key in booleanPreferenceTelemetryAllowList) {
                    val enabled = sharedPreferences.getBoolean(key, false)
                    Events.preferenceToggled.record(Events.PreferenceToggledExtra(enabled, key))
                }
            } catch (e: ClassCastException) {
                // The setting is not a boolean, not tracked
            }
        }

        findPreference<Preference>(getPreferenceKey(R.string.pref_key_translation))?.isVisible =
            FxNimbus.features.translations.value().globalSettingsEnabled &&
                components.core.store.state.translationEngine.isEngineSupported == true

        findPreference<Preference>(getPreferenceKey(R.string.pref_key_page_summaries))?.isVisible =
            components.settings.shakeToSummarizeFeatureFlagEnabled

        findPreference<Preference>(getPreferenceKey(R.string.pref_key_ai_controls))?.isVisible =
            requireComponents.settings.aiControlsFeatureFlagEnabled

        if (requireComponents.settings.accountSettingsNewUi) {
            findPreference<PreferenceCategory>(getPreferenceKey(R.string.pref_key_account_category))
                ?.setTitle(R.string.preferences_account_and_sync_settings)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Vantage: bind the inline search field to the live settings filter.
        findPreference<VantageSettingsSearchPreference>(
            getPreferenceKey(R.string.pref_key_vantage_settings_search),
        )?.onQueryChanged = ::filterSettings
    }

    /**
     * Vantage: shows only the settings that match [rawQuery], restoring the original visibility
     * once the search field is cleared.
     */
    private fun filterSettings(rawQuery: String) {
        val screen = preferenceScreen ?: return
        val query = rawQuery.trim()

        if (query.isEmpty()) {
            if (filteringSettings) {
                filteringSettings = false
                restoreVisibility(screen)
            }
            return
        }

        if (!filteringSettings) {
            filteringSettings = true
            snapshotVisibility(screen)
        }
        applyFilter(screen, query)
    }

    private fun snapshotVisibility(group: PreferenceGroup) {
        for (index in 0 until group.preferenceCount) {
            val preference = group.getPreference(index)
            if (preference.key == getPreferenceKey(R.string.pref_key_vantage_settings_search)) continue
            originalPreferenceVisibility[preference] = preference.isVisible
            if (preference is PreferenceGroup) {
                snapshotVisibility(preference)
            }
        }
    }

    private fun restoreVisibility(group: PreferenceGroup) {
        for (index in 0 until group.preferenceCount) {
            val preference = group.getPreference(index)
            preference.isVisible = originalPreferenceVisibility[preference] ?: preference.isVisible
            if (preference is PreferenceGroup) {
                restoreVisibility(preference)
            }
        }
    }

    private fun applyFilter(group: PreferenceGroup, query: String) {
        for (index in 0 until group.preferenceCount) {
            val preference = group.getPreference(index)
            if (preference.key == getPreferenceKey(R.string.pref_key_vantage_settings_search)) continue

            if (preference is PreferenceGroup) {
                val categoryMatches = preference.title?.toString()?.contains(query, ignoreCase = true) == true
                if (categoryMatches) {
                    restoreVisibility(preference)
                    preference.isVisible = true
                } else {
                    applyFilter(preference, query)
                    var hasVisibleChild = false
                    for (childIndex in 0 until preference.preferenceCount) {
                        if (preference.getPreference(childIndex).isVisible) {
                            hasVisibleChild = true
                            break
                        }
                    }
                    preference.isVisible = hasVisibleChild
                }
            } else {
                preference.isVisible = preferenceMatches(preference, query)
            }
        }
    }

    private fun preferenceMatches(preference: Preference, query: String): Boolean {
        val title = preference.title?.toString().orEmpty()
        val summary = preference.summary?.toString().orEmpty()
        val keywords = preference.key?.let { VANTAGE_SETTINGS_SEARCH_KEYWORDS[it] }.orEmpty()
        return title.contains(query, ignoreCase = true) ||
            summary.contains(query, ignoreCase = true) ||
            keywords.any { it.contains(query, ignoreCase = true) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                profilerViewModel.isProfilerActive.collect { isActive ->
                    updateProfilerUI(isActive)
                }
            }
        }

        snackbarBinding.set(
            feature =
                SnackbarBinding(
                    context = requireContext(),
                    browserStore = components.core.store,
                    appStore = components.appStore,
                    snackbarDelegate = FenixSnackbarDelegate(view),
                    navController = findNavController(),
                    tabsUseCases = components.useCases.tabsUseCases,
                    sendTabUseCases = null,
                    customTabSessionId = null,
                    applicationScope = components.applicationScope,
                ),
            owner = this,
            view = view,
        )
    }

    override fun onResume() {
        super.onResume()

        // Use nimbus to set the title, and a trivial addition
//        val nimbusValidation = FxNimbus.features.nimbusValidation.value()

        val title = getString(R.string.settings_title)
        val suffix = ""
        val toolbarTitle = "$title$suffix"

        val showSearch = !args.searchInProgress

        if (showSearch) {
            showToolbarWithIconButton(
                title = toolbarTitle,
                contentDescription = getString(R.string.settings_search_button_content_description),
                iconResId = iconsR.drawable.mozac_ic_search_24,
                onClick = {
                    SettingsSearch.opened.record()
                    findNavController().navigate(R.id.action_settingsFragment_to_settingsSearchFragment)
                },
            )
        } else {
            showToolbar(toolbarTitle)
        }

        // Account UI state is updated as part of `onCreate`. To not do it twice in a row, we only
        // update it here if we're not going through the `onCreate->onStart->onResume` lifecycle chain.
        update(
            shouldUpdateAccountUIState = !creatingFragment && requireComponents.settings.ironfox.fxaEnabled,
            settings = requireComponents.settings,
        )

        requireView()
            .findViewById<RecyclerView>(R.id.recycler_view)
            ?.hideInitialScrollBar(viewLifecycleOwner.lifecycleScope)

        args.preferenceToScrollTo?.let {
            scrollToPreferenceWithHighlight(it)
        }
        // Consider finish of `onResume` to be the point at which we consider this fragment as 'created'.
        creatingFragment = false
    }

    override fun onStart() {
        super.onStart()
        // Observe account changes to keep the UI up-to-date.
        if (requireComponents.settings.ironfox.fxaEnabled) {
            requireComponents.backgroundServices.accountManager.register(
                accountObserver,
                owner = this,
                autoPause = true,
            )
        }
    }

    override fun onStop() {
        super.onStop()
        // If the screen isn't visible we don't need to show updates.
        // Also prevent the observer registered to the FXA singleton causing memory leaks.
        if (requireComponents.settings.ironfox.fxaEnabled) {
            requireComponents.backgroundServices.accountManager.unregister(accountObserver)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        accountUiView.cancel()
    }

    private fun update(
        shouldUpdateAccountUIState: Boolean,
        settings: Settings,
    ) {
        val aboutPreference = requirePreference<Preference>(R.string.pref_key_about)
        val appName = getString(R.string.app_name)
        aboutPreference.title = getString(R.string.preferences_about, appName)

        val deleteBrowsingDataPreference =
            requirePreference<Preference>(R.string.pref_key_delete_browsing_data_on_quit_preference)
        deleteBrowsingDataPreference.summary =
            if (settings.shouldDeleteBrowsingDataOnQuit) {
                getString(R.string.delete_browsing_data_quit_on)
            } else {
                getString(R.string.delete_browsing_data_quit_off)
            }

        val tabSettingsPreference = requirePreference<Preference>(R.string.pref_key_tabs)
        tabSettingsPreference.summary = settings.getTabTimeoutString()

        val autofillPreference = requirePreference<Preference>(R.string.pref_key_credit_cards)
        autofillPreference.title =
            if (settings.addressFeature) {
                getString(R.string.preferences_autofill)
            } else {
                getString(R.string.preferences_credit_cards_2)
            }

        val openLinksInAppsSettingsPreference = requirePreference<Preference>(R.string.pref_key_open_links_in_apps)
        openLinksInAppsSettingsPreference.summary = settings.getOpenLinksInAppsString()

        setupPreferences(settings)

        if (shouldUpdateAccountUIState) {
            accountUiView.updateAccountUIState(
                requireContext(),
                requireComponents.backgroundServices.accountManager.accountProfile(),
            )
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        // Hide the scrollbar so the animation looks smoother
        val recyclerView = requireView().findViewById<RecyclerView>(R.id.recycler_view)
        recyclerView.isVerticalScrollBarEnabled = false

        val directions: NavDirections? =
            when (preference.key) {
                /* Top level account preferences.
                Note: Only ONE of these preferences is visible at a time. */
                resources.getString(R.string.pref_key_sign_in) -> {
                    SettingsMetrics.signIntoSync.add()
                    SettingsFragmentDirections.actionSettingsFragmentToTurnOnSyncFragment(
                        entrypoint = FenixFxAEntryPoint.SettingsMenu
                    )
                }

                resources.getString(R.string.pref_key_account) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToAccountSettingsFragment()
                }

                resources.getString(R.string.pref_key_account_auth_error) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToAccountProblemFragment(
                        entrypoint = FenixFxAEntryPoint.SettingsMenu
                    )
                }

                // General preferences
                resources.getString(R.string.pref_key_search_settings) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToSearchEngineFragment()
                }

                resources.getString(R.string.pref_key_tabs) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToTabsSettingsFragment()
                }

                resources.getString(R.string.pref_key_home) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToHomeSettingsFragment()
                }

                resources.getString(R.string.pref_key_customize) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToCustomizationFragment()
                }

                // Vantage: search, install and manage Firefox desktop themes.
                resources.getString(R.string.pref_key_vantage_themes) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToVantageThemesFragment()
                }

                resources.getString(R.string.pref_key_passwords) -> {
                    SettingsMetrics.passwords.record()
                    SettingsFragmentDirections.actionSettingsFragmentToSavedLoginsAuthFragment()
                }

                resources.getString(R.string.pref_key_email_masks) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToEmailMasksSettingsFragment()
                }

                resources.getString(R.string.pref_key_credit_cards) -> {
                    SettingsMetrics.autofill.record()
                    SettingsFragmentDirections.actionSettingsFragmentToAutofillSettingFragment()
                }

                resources.getString(R.string.pref_key_accessibility) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToAccessibilityFragment()
                }

                resources.getString(R.string.pref_key_language) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToLocaleSettingsFragment()
                }

                resources.getString(R.string.pref_key_translation) -> {
                    Translations.action.record(Translations.ActionExtra("global_settings_from_preferences"))
                    SettingsFragmentDirections.actionSettingsFragmentToTranslationsSettingsFragment()
                }

                resources.getString(R.string.pref_key_page_summaries) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToPageSummariesSettingsFragment()
                }

                resources.getString(R.string.pref_key_ai_controls) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToAiControlsFragment()
                }

                // Privacy and security preferences
                resources.getString(R.string.pref_key_private_browsing) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToPrivateBrowsingFragment()
                }

                resources.getString(R.string.pref_key_https_only_settings) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToHttpsOnlyFragment()
                }

                resources.getString(R.string.pref_key_ip_protection_settings) -> {
                    Vpn.settingsPageTapped.record(Vpn.SettingsPageTappedExtra(entrypoint = "Settings"))
                    SettingsFragmentDirections.actionSettingsFragmentToIpProtectionFragment(
                        entrypoint = FenixFxAEntryPoint.IPProtectionSettings
                    )
                }

                resources.getString(R.string.pref_key_tracking_protection_settings) -> {
                    TrackingProtection.etpSettings.record(NoExtras())
                    SettingsFragmentDirections.actionSettingsFragmentToTrackingProtectionFragment()
                }

                resources.getString(R.string.pref_key_doh_settings) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToDohSettingsFragment()
                }

                resources.getString(R.string.pref_key_site_permissions) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToSitePermissionsFragment()
                }

                resources.getString(R.string.pref_key_delete_browsing_data) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToDeleteBrowsingDataFragment()
                }

                resources.getString(R.string.pref_key_delete_browsing_data_on_quit_preference) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToDeleteBrowsingDataOnQuitFragment()
                }

                resources.getString(R.string.pref_key_notifications) -> {
                    context?.navigateToNotificationsSettings {}
                    null
                }

                resources.getString(R.string.pref_key_local_network_access) -> {
                    handleLocalNetworkPermissionClick()
                    null
                }

                resources.getString(R.string.pref_key_data_choices) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToDataChoicesFragment()
                }

                // Advanced preferences
                resources.getString(R.string.pref_key_addons) -> {
                    Addons.openAddonsInSettings.record(NoExtras())
                    SettingsFragmentDirections.actionSettingsFragmentToAddonsFragment()
                }

                // Only displayed when secret settings are enabled
                resources.getString(R.string.pref_key_install_local_addon) -> {
                    addonFilePicker.launch()
                    null
                }

                // Only displayed when secret settings are enabled
                resources.getString(R.string.pref_key_override_amo_collection) -> {
                    val context = requireContext()
                    val dialogView = LayoutInflater.from(context).inflate(R.layout.amo_collection_override_dialog, null)

                    val binding = AmoCollectionOverrideDialogBinding.bind(dialogView)
                    MaterialAlertDialogBuilder(context)
                        .apply {
                            setTitle(context.getString(R.string.preferences_customize_extension_collection))
                            setView(dialogView)
                            setNegativeButton(R.string.customize_addon_collection_cancel) { dialog: DialogInterface, _
                                ->
                                dialog.cancel()
                            }

                            setPositiveButton(R.string.customize_addon_collection_ok) { _, _ ->
                                context.components.settings.overrideAmoUser = binding.customAmoUser.text.toString()
                                context.components.settings.overrideAmoCollection =
                                    binding.customAmoCollection.text.toString()

                                Toast.makeText(
                                        context,
                                        getString(R.string.toast_customize_extension_collection_done),
                                        Toast.LENGTH_LONG,
                                    )
                                    .show()

                                Handler(Looper.getMainLooper())
                                    .postDelayed(
                                        {
                                            exitProcess(0)
                                        },
                                        AMO_COLLECTION_OVERRIDE_EXIT_DELAY,
                                    )
                            }

                            binding.customAmoCollection.setText(context.components.settings.overrideAmoCollection)
                            binding.customAmoUser.setText(context.components.settings.overrideAmoUser)
                            binding.customAmoUser.requestFocus()
                            binding.customAmoUser.showKeyboard()
                            create().withCenterAlignedButtons()
                        }
                        .show()

                    null
                }

                resources.getString(R.string.pref_key_link_sharing) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToLinkSharingFragment()
                }

                resources.getString(R.string.pref_key_remote_improvements) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToRemoteImprovementsFragment()
                }

                resources.getString(R.string.pref_key_open_links_in_apps) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToOpenLinksInAppsFragment()
                }

                resources.getString(R.string.pref_key_downloads) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToOpenDownloadsSettingsFragment()
                }

                resources.getString(R.string.pref_key_firefox_labs) -> {
                    SettingsMetrics.firefoxLabs.record()
                    SettingsFragmentDirections.actionSettingsFragmentToFirefoxLabsFragment()
                }

                resources.getString(R.string.pref_key_sync_debug) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToSyncDebugFragment()
                }

                // About preferences
                resources.getString(R.string.pref_key_rate) -> {
/*                    components.playStoreReviewPromptController.tryLaunchPlayStoreReview(
                        requireActivity(),
                        ::openInNewTab,
                    ) */
                    null
                }

                resources.getString(R.string.pref_key_about) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToAboutFragment()
                }

                // Only displayed when secret settings are enabled
                resources.getString(R.string.pref_key_debug_settings) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToSecretSettingsFragment()
                }

                // Only displayed when secret settings are enabled
                resources.getString(R.string.pref_key_nimbus_experiments) -> {
                    SettingsFragmentDirections.actionSettingsFragmentToNimbusExperimentsFragment()
                }

                // Only displayed when secret settings are enabled
                resources.getString(R.string.pref_key_start_profiler) -> {
                    if (profilerViewModel.isProfilerActive.value) {
                        SettingsFragmentDirections.actionSettingsFragmentToStopProfilerDialog()
                    } else {
                        SettingsFragmentDirections.actionSettingsFragmentToStartProfilerDialog()
                    }
                }

                else -> null
            }
        directions?.let { navigateFromSettings(directions) }
        return super.onPreferenceTreeClick(preference)
    }

    private fun setupPreferences(settings: Settings) {
        val leakKey = getPreferenceKey(R.string.pref_key_leakcanary)
        val debuggingKey = getPreferenceKey(R.string.pref_key_remote_debugging)
        val preferenceLeakCanary = findPreference<Preference>(leakKey)
        val preferenceRemoteDebugging = findPreference<Preference>(debuggingKey)
        val preferenceMakeDefaultBrowser =
            requirePreference<DefaultBrowserPreference>(R.string.pref_key_make_default_browser)

        if (!Config.channel.isReleased) {
            preferenceLeakCanary?.setOnPreferenceChangeListener { _, newValue ->
                val isEnabled = newValue == true
                context?.application?.updateLeakCanaryState(isEnabled)
                true
            }
        }

        preferenceRemoteDebugging?.isVisible = true
        preferenceRemoteDebugging?.setOnPreferenceChangeListener<Boolean> { preference, newValue ->
            settings.preferences.edit { putBoolean(preference.key, newValue) }
            requireComponents.core.engine.settings.remoteDebuggingEnabled = newValue
            true
        }

        preferenceMakeDefaultBrowser.apply {
            updateSwitch()
            onPreferenceClickListener = getClickListenerForMakeDefaultBrowser()
        }

        val preferenceStartProfiler = findPreference<Preference>(getPreferenceKey(R.string.pref_key_start_profiler))

        with(settings) {
            findPreference<Preference>(getPreferenceKey(R.string.pref_key_nimbus_experiments))?.isVisible =
                showSecretDebugMenuThisSession
            findPreference<Preference>(getPreferenceKey(R.string.pref_key_debug_settings))?.isVisible =
                showSecretDebugMenuThisSession
            findPreference<Preference>(getPreferenceKey(R.string.pref_key_sync_debug))?.isVisible =
                showSecretDebugMenuThisSession
            preferenceStartProfiler?.isVisible =
                showSecretDebugMenuThisSession && (components.core.engine.profiler?.isProfilerActive() != null)
        }
        setupInstallAddonFromFilePreference(settings)
        setLinkSharingPreference()
        setupAmoCollectionOverridePreference(
            settings,
            FeatureFlags.customExtensionCollectionFeature,
        )
        setupGeckoLogsPreference(settings)
        setupHttpsOnlyPreferences(settings)
        setupIPProtectionPreferences(components.ipProtection.store)
        setupNotificationPreference(NotificationManagerCompat.from(requireContext()).areNotificationsEnabled())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            setupLocalNetworkPreference(
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    ACCESS_LOCAL_NETWORK,
                ) == PackageManager.PERMISSION_GRANTED
            )
        } else {
            requirePreference<Preference>(R.string.pref_key_local_network_access).isVisible = false
        }
        setupSearchPreference(components.core.store.state.search.selectedOrDefaultSearchEngine?.name)
        setupHomepagePreference(settings)
        setupTrackingProtectionPreference(settings)
        setupDnsOverHttpsPreference(settings)
        setupEmailMaskPreference(settings, requireComponents)
        setupAutofillPreferences(settings)
        setupIronFoxPreferences()
    }

    internal fun setupIronFoxPreferences() {
        requirePreference<Preference>(R.string.pref_key_if_preferences).apply {
            setOnPreferenceClickListener {
                val directions =
                    SettingsFragmentDirections
                        .actionSettingsFragmentToIronFoxSettingsFragment()
                requireView().findNavController().navigate(directions)
                true
            }
        }
        FenixSettingsVisibilityManager.SettingsFragment(requireContext(), this)
     }

    private fun setupAutofillPreferences(settings: Settings) {
        if (settings.isAutofillSupported) {
            return
        }

        requirePreference<Preference>(R.string.pref_key_passwords).isVisible = false
        requirePreference<Preference>(R.string.pref_key_credit_cards).isVisible = false
    }

    private val setToDefaultPromptRequestLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            with(requireContext()) {
                maybeNavigateToSystemSetToDefaultAction(result.resultCode, components.settings, dateTimeProvider) {
                    navigateToDefaultBrowserAppsSettings(BuildManufacturerChecker())
                }
            }
        }

    /**
     * For >=Q -> Use new RoleManager API to show in-app browser switching dialog. For <Q && >=N -> Navigate user to
     * Android Default Apps Settings. For <N -> Open sumo page to show user how to change default app.
     */
    private fun getClickListenerForMakeDefaultBrowser(): Preference.OnPreferenceClickListener {
        return Preference.OnPreferenceClickListener {
            maybeRequestDefaultBrowserPrompt(
                WeakReference((requireActivity() as? HomeActivity)),
                setToDefaultPromptRequestLauncher,
            )
            true
        }
    }

    private fun navigateFromSettings(directions: NavDirections) {
        view?.findNavController()?.let { navController ->
            if (navController.currentDestination?.id == R.id.settingsFragment) {
                navController.navigate(directions)
            }
        }
    }

    // Extension function for hiding the scroll bar on initial loading. We must do this so the
    // animation to the next screen doesn't animate the initial scroll bar (it ignores
    // isVerticalScrollBarEnabled being set to false).
    private fun RecyclerView.hideInitialScrollBar(scope: CoroutineScope) {
        scope.launch {
            val originalSize = scrollBarSize
            scrollBarSize = 0
            delay(SCROLL_INDICATOR_DELAY)
            scrollBarSize = originalSize
        }
    }

    @VisibleForTesting
    internal fun setupAmoCollectionOverridePreference(
        settings: Settings,
        customExtensionCollectionFeature: Boolean,
    ) {
        val preferenceAmoCollectionOverride =
            findPreference<Preference>(getPreferenceKey(R.string.pref_key_override_amo_collection))

        val show =
            (customExtensionCollectionFeature &&
                (settings.amoCollectionOverrideConfigured() || settings.showSecretDebugMenuThisSession))
        preferenceAmoCollectionOverride?.apply {
            isVisible = show
            summary = settings.overrideAmoCollection.ifEmpty { null }
        }
    }

    @VisibleForTesting
    internal fun setupGeckoLogsPreference(settings: Settings) {
        val preferenceEnabledGeckoLogs =
            findPreference<Preference>(getPreferenceKey(R.string.pref_key_enable_gecko_logs))

        val show = settings.showSecretDebugMenuThisSession
        preferenceEnabledGeckoLogs?.isVisible = show

        preferenceEnabledGeckoLogs?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            settings.enableGeckoLogs = newValue as Boolean
            Toast.makeText(
                    context,
                    getString(R.string.quit_application),
                    Toast.LENGTH_LONG,
                )
                .show()
            Handler(Looper.getMainLooper())
                .postDelayed(
                    {
                        exitProcess(0)
                    },
                    FXA_SYNC_OVERRIDE_EXIT_DELAY,
                )
            true
        }
    }

    @VisibleForTesting
    internal fun setupNotificationPreference(areNotificationsEnabled: Boolean) {
        with(requirePreference<Preference>(R.string.pref_key_notifications)) {
            summary =
                if (areNotificationsEnabled) {
                    getString(R.string.notifications_allowed_summary)
                } else {
                    getString(R.string.notifications_not_allowed_summary)
                }
        }
    }

    private fun handleLocalNetworkPermissionClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            val isGranted =
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    ACCESS_LOCAL_NETWORK,
                ) == PackageManager.PERMISSION_GRANTED

            if (isGranted) {
                context?.navigateToAppDetailsSettings {}
            } else {
                val shouldShowRationale = shouldShowRequestPermissionRationale(ACCESS_LOCAL_NETWORK)
                if (shouldShowRationale || !localNetworkPermissionRequestAttempted) {
                    requestLocalNetworkPermissionLauncher.launch(ACCESS_LOCAL_NETWORK)
                } else {
                    context?.navigateToAppDetailsSettings {}
                }
            }
        } else {
            context?.navigateToAppDetailsSettings {}
        }
    }

    internal fun setupLocalNetworkPreference(isLocalNetworkAccessAllowed: Boolean) {
        with(requirePreference<Preference>(R.string.pref_key_local_network_access)) {
            summary =
                if (isLocalNetworkAccessAllowed) {
                    getString(R.string.local_network_access_allowed_summary)
                } else {
                    getString(R.string.local_network_access_not_allowed_summary)
                }
        }
    }

    @VisibleForTesting
    internal fun setupHomepagePreference(settings: Settings) {
        with(requirePreference<Preference>(R.string.pref_key_home)) {
            summary =
                when {
                    settings.alwaysOpenTheHomepageWhenOpeningTheApp ->
                        getString(R.string.opening_screen_homepage_summary)

                    settings.openHomepageAfterFourHoursOfInactivity ->
                        getString(R.string.opening_screen_after_four_hours_of_inactivity_summary)

                    settings.alwaysOpenTheLastTabWhenOpeningTheApp ->
                        getString(R.string.opening_screen_last_tab_summary)

                    else -> null
                }
        }
    }

    @VisibleForTesting
    internal fun setupSearchPreference(selectedOrDefaultSearchEngineName: String?) {
        with(requirePreference<Preference>(R.string.pref_key_search_settings)) {
            summary = selectedOrDefaultSearchEngineName
        }
    }

    @VisibleForTesting
    internal fun setupTrackingProtectionPreference(settings: Settings) {
        with(requirePreference<Preference>(R.string.pref_key_tracking_protection_settings)) {
            summary =
                when {
                    !settings.shouldUseTrackingProtection -> getString(R.string.tracking_protection_off)
                    settings.useStandardTrackingProtection -> getString(R.string.tracking_protection_standard)
                    settings.useStrictTrackingProtection -> getString(R.string.tracking_protection_strict)
                    settings.useCustomTrackingProtection -> getString(R.string.tracking_protection_custom)
                    else -> null
                }
        }
    }

    private fun setupDnsOverHttpsPreference(settings: Settings) {
        with(requirePreference<Preference>(R.string.pref_key_doh_settings)) {
            isVisible = settings.showDohEntryPoint
            summary =
                when (settings.getDohSettingsMode()) {
                    Engine.DohSettingsMode.DEFAULT -> getString(R.string.preference_doh_default_protection)
                    Engine.DohSettingsMode.OFF -> getString(R.string.preference_doh_off)
                    Engine.DohSettingsMode.INCREASED -> getString(R.string.preference_doh_increased_protection)
                    Engine.DohSettingsMode.MAX -> getString(R.string.preference_doh_max_protection)
                }
        }
    }

    @VisibleForTesting
    internal fun setupEmailMaskPreference(settings: Settings, components: Components) {
        findPreference<Preference>(getPreferenceKey(R.string.pref_key_email_masks))?.let {
            it.isVisible =
                settings.isEmailMaskFeatureEnabled &&
                    components.relayEligibilityStore.state.eligibilityState is Eligible
        }
    }

    @VisibleForTesting
    internal fun setupInstallAddonFromFilePreference(settings: Settings) {
        with(requirePreference<Preference>(R.string.pref_key_install_local_addon)) {
            isVisible = settings.showSecretDebugMenuThisSession
        }
    }

    @VisibleForTesting
    internal fun setLinkSharingPreference() {
        with(requirePreference<Preference>(R.string.pref_key_link_sharing)) {
            isVisible = FxNimbus.features.sentFromFirefox.value().enabled
        }
    }

    @VisibleForTesting
    internal fun setupHttpsOnlyPreferences(settings: Settings) {
        val httpsOnlyPreference = requirePreference<Preference>(R.string.pref_key_https_only_settings)
        httpsOnlyPreference.summary =
            when {
                !settings.shouldUseHttpsOnly -> getString(R.string.preferences_https_only_off)
                settings.shouldUseHttpsOnlyInAllTabs -> getString(R.string.preferences_https_only_on_all)
                settings.shouldUseHttpsOnlyInPrivateTabsOnly -> getString(R.string.preferences_https_only_on_private)
                else -> null
            }
    }

    @VisibleForTesting
    internal fun setupIPProtectionPreferences(ipProtectionStore: IPProtectionStore) {
        findPreference<IPProtectionPreference>(getPreferenceKey(R.string.pref_key_ip_protection_settings))?.apply {
            isVisible = ipProtectionStore.state.isEligible
            showBetaBadge = FxNimbus.features.ipProtection.value().showBetaBadge
        }
    }

    private fun updateProfilerUI(profilerStatus: Boolean) {
        if (profilerStatus) {
            findPreference<Preference>(getPreferenceKey(R.string.pref_key_start_profiler))?.title =
                resources.getString(R.string.profiler_stop)
            findPreference<Preference>(getPreferenceKey(R.string.pref_key_start_profiler))?.summary =
                resources.getString(R.string.profiler_running)
        } else {
            findPreference<Preference>(getPreferenceKey(R.string.pref_key_start_profiler))?.title =
                resources.getString(R.string.preferences_start_profiler)
            findPreference<Preference>(getPreferenceKey(R.string.pref_key_start_profiler))?.summary = ""
        }
    }

    companion object {
        private const val SCROLL_INDICATOR_DELAY = 10L
        private const val FXA_SYNC_OVERRIDE_EXIT_DELAY = 2000L
        private const val AMO_COLLECTION_OVERRIDE_EXIT_DELAY = 3000L

        /**
         * Vantage: extra search terms for settings whose actual options live inside a sub-screen,
         * so searching for e.g. "主题" still surfaces the "定制" entry.
         */
        private val VANTAGE_SETTINGS_SEARCH_KEYWORDS =
            mapOf(
                "pref_key_search_settings" to listOf("搜索", "搜索引擎", "search", "engine"),
                "pref_key_tabs" to listOf("标签页", "标签", "关闭标签", "超时", "tabs", "tab", "timeout"),
                "pref_key_accessibility" to listOf("无障碍", "字体", "缩放", "accessibility", "font", "zoom"),
                "pref_key_language" to listOf("语言", "language", "locale"),
                "pref_key_translation" to listOf("翻译", "translate", "translation"),
                "pref_key_page_summaries" to listOf("摘要", "总结", "summarize", "summary"),
                "pref_key_ai_controls" to listOf("ai", "人工智能", "智能"),
                "pref_key_make_default_browser" to listOf("默认浏览器", "default browser"),
                "pref_key_customize" to listOf(
                    "主题", "外观", "图标", "颜色", "深色", "工具栏", "标签栏", "手势",
                    "theme", "appearance", "icon", "color", "toolbar", "tab strip", "gesture",
                ),
                "pref_key_vantage_themes" to listOf(
                    "桌面主题", "安装主题", "主题", "desktop theme", "install theme", "theme",
                ),
                "pref_key_home" to listOf("主页", "壁纸", "新标签页", "快捷方式", "home", "wallpaper", "shortcut"),
                "pref_key_passwords" to listOf("密码", "登录", "password", "login"),
                "pref_key_credit_cards" to listOf("信用卡", "自动填充", "autofill", "credit card"),
                "pref_key_email_masks" to listOf("邮箱掩码", "email mask", "relay"),
                "pref_key_private_browsing" to listOf("隐私", "无痕", "private", "incognito"),
                "pref_key_ip_protection_settings" to listOf("ip 保护", "vpn", "ip protection"),
                "pref_key_https_only_settings" to listOf("https", "加密", "encryption", "secure"),
                "pref_key_doh_settings" to listOf("dns", "doh", "加密 dns"),
                "pref_key_tracking_protection_settings" to listOf(
                    "跟踪保护", "增强型跟踪保护", "cookie", "tracking", "etp", "protection",
                ),
                "pref_key_site_permissions" to listOf("网站设置", "权限", "site settings", "permission"),
                "pref_key_delete_browsing_data" to listOf("删除浏览数据", "历史", "缓存", "delete browsing data", "history", "cache"),
                "pref_key_delete_browsing_data_on_quit_preference" to listOf("退出时删除", "on quit", "quit"),
                "pref_key_notifications" to listOf("通知", "notification"),
                "pref_key_data_choices" to listOf("数据收集", "遥测", "data collection", "telemetry", "usage"),
                "pref_key_downloads" to listOf("下载", "download", "文件"),
                "pref_key_link_sharing" to listOf("链接分享", "分享", "link sharing", "share"),
                "pref_key_open_links_in_apps" to listOf("外部应用", "打开链接", "open links in apps", "external app"),
                "pref_key_addons" to listOf("扩展", "插件", "extension", "add-on"),
                "pref_key_override_amo_collection" to listOf("扩展集合", "extension collection"),
                "pref_key_firefox_labs" to listOf("实验室", "labs"),
                "pref_key_remote_improvements" to listOf("远程改进", "remote improvements"),
                "pref_key_remote_debugging" to listOf("远程调试", "remote debugging"),
                "pref_key_enable_gecko_logs" to listOf("gecko 日志", "日志", "logs"),
                "pref_key_local_network_access" to listOf("本地网络", "local network"),
                "pref_key_if_preferences" to listOf(
                    "vantage", "ironfox", "jit", "safe browsing", "安全", "性能", "隐私",
                ),
                "pref_key_rate" to listOf("评分", "rate", "review"),
                "pref_key_about" to listOf("关于", "about", "版本", "version"),
            )
    }
}
