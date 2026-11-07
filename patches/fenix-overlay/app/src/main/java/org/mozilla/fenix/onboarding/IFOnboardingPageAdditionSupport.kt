package org.mozilla.fenix.onboarding

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mozilla.components.support.base.feature.LifecycleAwareFeature
import org.ironfoxoss.core.IFConstants
import org.mozilla.fenix.R
import org.mozilla.fenix.onboarding.view.IfPreferenceOption
import org.mozilla.fenix.onboarding.view.IfPreferenceType
import org.mozilla.fenix.onboarding.view.OnboardingPageUiData
import kotlin.coroutines.CoroutineContext

/**
 * Handles adding and removing onboarding pages if certain conditions are met.
 *
 * @param pagesToDisplay the mutable list of onboarding pages we display
 * @param mainContext the coroutine context for UI
 * @param ioContext the coroutine context for IO
 * @param lifecycleOwner the lifecycle owner
 */
class IFOnboardingPageAdditionSupport(
  private val pagesToDisplay: MutableList<OnboardingPageUiData>,
  private val mainContext: CoroutineContext = Dispatchers.Main,
  private val ioContext: CoroutineContext = Dispatchers.IO,
  private val lifecycleOwner: LifecycleOwner,
) : LifecycleAwareFeature {

  var currentPageIndex: Int = 0

  private var job: Job? = null

  override fun start() {
    job = lifecycleOwner.lifecycleScope.launch(ioContext) {
      // Remove unwanted onboarding pages
      pagesToDisplay.removeIfPageNotReached(currentPageIndex)
      // Vantage: make sure the personalisation step is always part of the flow.
      pagesToDisplay.ensurePreferencesPage()
    }
  }

  override fun stop() {
    job?.cancel()
  }
}

// Remove unwanted onboarding pages
internal fun MutableList<OnboardingPageUiData>.removeIfPageNotReached(index: Int) {
  val searchWidgetIndex = indexOfFirst { it.type == OnboardingPageUiData.Type.ADD_SEARCH_WIDGET }
  val syncIndex = indexOfFirst { it.type == OnboardingPageUiData.Type.SYNC_SIGN_IN }
  val updaterIndex = indexOfFirst { it.type == OnboardingPageUiData.Type.IF_PREFERENCE_UPDATER }

  if (index < syncIndex) {
    removeAt(syncIndex)
  }

  if (index < searchWidgetIndex) {
    removeAt(searchWidgetIndex)
  }

  if (!IFConstants.IRONFOX_UPDATER && index < updaterIndex) {
    removeAt(updaterIndex)
  }
}

// Vantage: the bundled onboarding configuration does not always include our personalisation
// step, so insert it ahead of the DoH page when it is missing.
internal fun MutableList<OnboardingPageUiData>.ensurePreferencesPage() {
  if (any { it.type == OnboardingPageUiData.Type.IF_PREFERENCES }) {
    return
  }

  val page =
    OnboardingPageUiData(
      type = OnboardingPageUiData.Type.IF_PREFERENCES,
      imageRes = R.drawable.ic_onboarding_welcome,
      title = "个性化配置",
      description = "选择 Vantage 默认开启的保护与性能选项，之后都可以在设置中修改。",
      primaryButtonLabel = "继续",
      ifPreferencesData =
        listOf(
          IfPreferenceOption(
            label = "JavaScript JIT",
            caption = "让网页脚本运行更快",
            description = "为网页脚本启用即时编译（JIT），提升页面加载与交互速度。",
            preferenceType = IfPreferenceType.JS_JIT,
          ),
          IfPreferenceOption(
            label = "Safe Browsing",
            caption = "拦截钓鱼与恶意网站",
            description = "启用 Safe Browsing，在访问危险网站前提醒并阻止加载。",
            preferenceType = IfPreferenceType.SAFE_BROWSING,
          ),
        ),
    )

  val dohIndex = indexOfFirst { it.type == OnboardingPageUiData.Type.IF_PREFERENCE_DOH }
  add(if (dohIndex >= 0) dohIndex else size, page)
}
