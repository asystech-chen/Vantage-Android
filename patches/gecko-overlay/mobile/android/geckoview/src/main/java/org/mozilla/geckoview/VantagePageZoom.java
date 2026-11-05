/* Vantage: helper for applying a true full-page zoom to a GeckoSession. */
package org.mozilla.geckoview;

import org.mozilla.gecko.util.GeckoBundle;

public final class VantagePageZoom {
  private VantagePageZoom() {}

  /**
   * Sets the full page zoom factor for the given session.
   *
   * @param session the session to zoom.
   * @param zoom the zoom factor, where 1.0 is 100%.
   */
  public static void setPageZoom(final GeckoSession session, final float zoom) {
    final GeckoBundle bundle = new GeckoBundle(1);
    bundle.putDouble("zoom", zoom);
    session.getEventDispatcher().dispatch("GeckoView:SetPageZoom", bundle);
  }
}
