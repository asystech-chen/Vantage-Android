/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.onboarding

import android.content.Context

/**
 * Vantage: tracks which onboarding tours the user has finished, mirroring the desktop
 * `vantage.onboarding.currentTour` / `completedTours` prefs. Bumping [CURRENT_TOUR] shows
 * the guide once more for people who already use Vantage, without replaying it afterwards.
 */
object VantageOnboardingTour {
    const val CURRENT_TOUR = "v1"

    private const val PREFS = "vantage_onboarding"
    private const val COMPLETED_TOURS = "vantage_onboarding_completed_tours"

    fun shouldShow(context: Context): Boolean = CURRENT_TOUR !in completedTours(context)

    fun markCompleted(context: Context) {
        val completed = completedTours(context) + CURRENT_TOUR
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(COMPLETED_TOURS, completed)
            .apply()
    }

    private fun completedTours(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(COMPLETED_TOURS, emptySet())
            ?.toSet()
            .orEmpty()
}
