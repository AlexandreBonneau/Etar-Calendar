/*
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.android.calendar.categories

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.android.calendar.Utils
import com.android.calendar.categories.EventCategories.normalize
import com.android.calendar.settings.GeneralPreferences
import org.json.JSONException
import org.json.JSONObject

/**
 * Colors events according to their iCalendar CATEGORIES (cf. issue #491).
 *
 * Rules :
 *  - feature disabled -> nothing changes (calendar / event colors as before);
 *  - feature enabled -> the first category of the event decides the color ; if the event has no
 *    category, or if its _first_ category has no configured color, the "no category" color is used.
 *
 * The categories themselves are read by [EventCategories].
 */
object CategoryColors {
    private const val TAG = "CategoryColors"

    const val KEY_ENABLED = "pref_category_colors_enabled"
    const val KEY_NO_CATEGORY_COLOR = "pref_category_colors_no_category_color"
    /** Use the color of the first category in the header of the event details */
    const val KEY_DETAILS_HEADER = "pref_category_colors_details_header"
    private const val KEY_COLOR_MAP = "pref_category_colors_map"

    /** Default "no category" color (light green #97eb79). */
    const val DEFAULT_NO_CATEGORY_COLOR = 0xFF97EB79.toInt()

    // --- Cache: configured colors, key = lower-cased category name ---------------------------

    @Volatile
    private var colorsCache: Map<String, Int>? = null

    private fun prefs(context: Context): SharedPreferences = GeneralPreferences.getSharedPreferences(context)

    // ------------------------------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------------------------------

    @JvmStatic
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** Whether the event details header uses the color of the first category */
    @JvmStatic
    fun isDetailsHeaderEnabled(context: Context): Boolean =
        isEnabled(context) && prefs(context).getBoolean(KEY_DETAILS_HEADER, false)

    /** Whether the loaders must read the categories (colors or filter in use). */
    @JvmStatic
    fun needsCategories(context: Context): Boolean = isEnabled(context)

    /** Categories offered in the event editor. */
    @JvmStatic
    fun getSuggestedCategories(context: Context): List<String> {
        return getAllKnownCategories(context)
    }

    @JvmStatic
    fun getNoCategoryColor(context: Context): Int =
        prefs(context).getInt(KEY_NO_CATEGORY_COLOR, DEFAULT_NO_CATEGORY_COLOR)

    @JvmStatic
    fun setNoCategoryColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_NO_CATEGORY_COLOR, color).apply()
    }

    /** True when the user changed the "no category" color. */
    @JvmStatic
    fun isNoCategoryColorCustomized(context: Context): Boolean =
        prefs(context).contains(KEY_NO_CATEGORY_COLOR)

    /** Restores the default "no category" color ([DEFAULT_NO_CATEGORY_COLOR]). */
    @JvmStatic
    fun clearNoCategoryColor(context: Context) {
        prefs(context).edit().remove(KEY_NO_CATEGORY_COLOR).apply()
    }

    /** Configured colors, keyed by category name as the user first saw it. */
    @JvmStatic
    fun getConfiguredColors(context: Context): Map<String, Int> {
        val json = prefs(context).getString(KEY_COLOR_MAP, null) ?: return emptyMap()
        val result = LinkedHashMap<String, Int>()
        try {
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                result[name] = obj.getInt(name)
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Invalid category color map, ignoring it", e)
        }
        return result
    }

    private fun saveConfiguredColors(context: Context, colors: Map<String, Int>) {
        val obj = JSONObject()
        colors.forEach { (name, color) -> obj.put(name, color) }
        prefs(context).edit().putString(KEY_COLOR_MAP, obj.toString()).apply()
        colorsCache = null
    }

    @JvmStatic
    fun setCategoryColor(context: Context, category: String, color: Int) {
        val colors = getConfiguredColors(context).toMutableMap()
        // Replace any entry differing only by case so we keep a single key per category
        colors.keys.filter { normalize(it) == normalize(category) }.forEach { colors.remove(it) }
        colors[category.trim()] = color
        saveConfiguredColors(context, colors)
    }

    /** Removes the configured color : the category falls back to the "no category" color */
    @JvmStatic
    fun clearCategoryColor(context: Context, category: String) {
        val colors = getConfiguredColors(context).toMutableMap()
        colors.keys.filter { normalize(it) == normalize(category) }.forEach { colors.remove(it) }
        saveConfiguredColors(context, colors)
    }

    /** Color chosen by the user for the category, or null if none */
    @JvmStatic
    fun getUserCategoryColor(context: Context, category: String): Int? {
        val cache = colorsCache ?: getConfiguredColors(context)
            .mapKeys { normalize(it.key) }
            .also { colorsCache = it }
        return cache[normalize(category)]
    }

    /** Effective color of the category : the color chosen by the user, else null */
    @JvmStatic
    fun getCategoryColor(context: Context, category: String): Int? =
        getUserCategoryColor(context, category)

    /** True when the user has chosen a color for this category */
    @JvmStatic
    fun isCustomized(context: Context, category: String): Boolean = getUserCategoryColor(context, category) != null

    /** Effective color of a badge : category color, else the "no category" color */
    @JvmStatic
    fun getBadgeColor(context: Context, category: String): Int =
        getCategoryColor(context, category) ?: getNoCategoryColor(context)

    /**
     * Every category to show in the settings: the configured ones and the ones found in the
     * calendars (so a category that disappeared from the server keeps its configured color).
     * Those are sorted alphabetically, case-insensitive, and one entry per category.
     */
    @JvmStatic
    fun getAllKnownCategories(context: Context): List<String> =
        EventCategories.sortedUnique(getConfiguredColors(context).keys +
                EventCategories.getEventCategories(context).values.flatten())

    // ------------------------------------------------------------------------------------------
    // Color resolution
    // ------------------------------------------------------------------------------------------

    /** Color of an event in category mode (no darkening applied) */
    @JvmStatic
    fun getColorForEvent(context: Context, eventId: Long): Int {
        val first = EventCategories.getEventCategories(context)[eventId]?.firstOrNull()
            ?: return getNoCategoryColor(context)
        return getCategoryColor(context, first) ?: getNoCategoryColor(context)
    }

    /**
     * Drop-in replacement for [Utils.getDisplayColorFromColor] when the event id is known :
     * category color if the feature is enabled, usual display color otherwise
     */
    @JvmStatic
    fun getDisplayColor(context: Context, eventId: Long, rawColor: Int): Int =
        if (isEnabled(context)) getColorForEvent(context, eventId)
        else Utils.getDisplayColorFromColor(context, rawColor)
}
