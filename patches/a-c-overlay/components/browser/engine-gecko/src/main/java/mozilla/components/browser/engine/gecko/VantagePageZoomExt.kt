/* Vantage: expose full page zoom on the Gecko engine session. */
package mozilla.components.browser.engine.gecko

import org.mozilla.geckoview.VantagePageZoom

/** Applies a full page zoom factor (1.0 = 100%) to this Gecko session. */
fun GeckoEngineSession.setPageZoomFactor(zoom: Float) {
    VantagePageZoom.setPageZoom(geckoSession, zoom)
}
