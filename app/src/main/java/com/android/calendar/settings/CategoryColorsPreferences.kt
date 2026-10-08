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

package com.android.calendar.settings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.android.calendar.Utils
import com.android.calendar.categories.CategoryColors
import com.android.calendar.categories.EventCategories
import ws.xsoh.etar.R

/**
 * Settings screen of the category colors : enables the feature, sets the color of
 * events without category and lists every known category alphabetically, with its color.
 * Changes are saved immediately, like the other Etar settings.
 */
class CategoryColorsPreferences : PreferenceFragmentCompat() {

    private lateinit var noCategoryPref: ColorSwatchPreference
    private lateinit var categoriesGroup: PreferenceCategory
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var loadGeneration = 0

    /** Category being added with "Add a category" needs to be saved even if the default color is not changed */
    private var pendingNewCategory: String? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        pendingNewCategory = savedInstanceState?.getString(STATE_PENDING_NEW_CATEGORY)
        preferenceManager.sharedPreferencesName = GeneralPreferences.SHARED_PREFS_NAME
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        val enabledPref = SwitchPreference(context).apply {
            key = CategoryColors.KEY_ENABLED
            setTitle(R.string.preferences_category_colors_enable)
            setSummary(R.string.preferences_category_colors_enable_summary)
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                Utils.sendUpdateWidgetIntent(requireContext())
                true
            }
        }

        val detailsHeaderPref = SwitchPreference(context).apply {
            key = CategoryColors.KEY_DETAILS_HEADER
            setTitle(R.string.preferences_category_colors_details_header)
            setSummary(R.string.preferences_category_colors_details_header_summary)
            setDefaultValue(false)
        }

        noCategoryPref = ColorSwatchPreference(context).apply {
            key = "pref_category_colors_no_category_item"
            setTitle(R.string.preferences_category_colors_no_category)
            setSummary(R.string.preferences_category_colors_no_category_summary)
            setOnPreferenceClickListener {
                val context = requireContext()
                showColorDialog(null, CategoryColors.getNoCategoryColor(context),
                    CategoryColors.isNoCategoryColorCustomized(context))
                true
            }
        }

        categoriesGroup = PreferenceCategory(context).apply {
            key = "pref_category_colors_list"
            setTitle(R.string.preferences_category_colors_list)
        }

        screen.addPreference(enabledPref)
        screen.addPreference(detailsHeaderPref)
        screen.addPreference(noCategoryPref)
        screen.addPreference(categoriesGroup)
        preferenceScreen = screen
        // Dependencies are resolved through the PreferenceManager : the screen must be set first.
        detailsHeaderPref.dependency = CategoryColors.KEY_ENABLED
        noCategoryPref.dependency = CategoryColors.KEY_ENABLED
        categoriesGroup.dependency = CategoryColors.KEY_ENABLED

        childFragmentManager.setFragmentResultListener(
            CategoryColorDialog.REQUEST_KEY, this
        ) { _, result -> onColorChosen(result) }
        childFragmentManager.setFragmentResultListener(
            CategoryNameDialog.REQUEST_KEY, this
        ) { _, result -> onNameChosen(result) }
    }

    override fun onResume() {
        super.onResume()
        activity?.title = getString(R.string.preferences_category_colors_title)
        refresh()
    }

    override fun onDestroy() {
        loadGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun refresh() {
        val context = requireContext().applicationContext
        noCategoryPref.swatchColor = CategoryColors.getNoCategoryColor(context)

        if (categoriesGroup.preferenceCount == 0) {
            showMessage(R.string.preferences_category_colors_loading)
        }
        // Reading the calendar provider must not block the UI thread !
        val generation = ++loadGeneration
        Thread {
            val categories = CategoryColors.getAllKnownCategories(context)
            mainHandler.post {
                if (generation == loadGeneration && isAdded) {
                    populate(categories)
                }
            }
        }.start()
    }

    private fun populate(categories: List<String>) {
        val context = requireContext()
        categoriesGroup.removeAll()
        categoriesGroup.addPreference(Preference(preferenceManager.context).apply {
            key = "pref_category_colors_add"
            isPersistent = false
            setIcon(R.drawable.ic_add)
            setTitle(R.string.preferences_category_colors_add)
            setOnPreferenceClickListener {
                if (childFragmentManager.findFragmentByTag(NAME_DIALOG_TAG) == null) {
                    CategoryNameDialog().show(childFragmentManager, NAME_DIALOG_TAG)
                }
                true
            }
        })
        if (categories.isEmpty()) {
            categoriesGroup.addPreference(messagePreference(R.string.preferences_category_colors_empty))
            return
        }
        val noCategoryColor = CategoryColors.getNoCategoryColor(context)
        for (category in categories) {
            val color = CategoryColors.getCategoryColor(context, category)
            val customized = CategoryColors.isCustomized(context, category)
            categoriesGroup.addPreference(ColorSwatchPreference(preferenceManager.context).apply {
                key = "pref_category_color_item:$category"
                title = category
                swatchColor = color ?: noCategoryColor
                summary = if (color == null) {
                    getString(R.string.preferences_category_colors_not_set)
                } else {
                    null
                }
                setOnPreferenceClickListener {
                    showColorDialog(category, color ?: noCategoryColor, customized)
                    true
                }
            })
        }
    }

    private fun showMessage(messageRes: Int) {
        categoriesGroup.removeAll()
        categoriesGroup.addPreference(messagePreference(messageRes))
    }

    private fun messagePreference(messageRes: Int) =
        Preference(preferenceManager.context).apply {
            isPersistent = false
            isSelectable = false
            setSummary(messageRes)
        }

    /** A new category was typed : ask for its color (existing ones keep their current color) */
    private fun onNameChosen(result: Bundle) {
        val context = requireContext()
        val name = EventCategories.sanitizeCategoryName(
            result.getString(CategoryNameDialog.RESULT_NAME)) ?: return
        val color = CategoryColors.getBadgeColor(context, name)
        pendingNewCategory = name
        showColorDialog(name, color, CategoryColors.isCustomized(context, name))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_NEW_CATEGORY, pendingNewCategory)
    }

    private fun showColorDialog(category: String?, color: Int, allowReset: Boolean) {
        if (childFragmentManager.findFragmentByTag(DIALOG_TAG) != null) return
        CategoryColorDialog.newInstance(category, color, allowReset)
            .show(childFragmentManager, DIALOG_TAG)
    }

    private fun onColorChosen(result: Bundle) {
        val context = requireContext()
        val category = result.getString(CategoryColorDialog.RESULT_CATEGORY)
        val reset = result.getBoolean(CategoryColorDialog.RESULT_RESET)
        val color = result.getInt(CategoryColorDialog.RESULT_COLOR)
        val colorChanged = result.getBoolean(CategoryColorDialog.RESULT_COLOR_CHANGED)
        // A category added with "Add a category" only exists once its color is saved
        val isNewCategory = category != null && category == pendingNewCategory
        pendingNewCategory = null
        when {
            category == null && reset -> CategoryColors.clearNoCategoryColor(context)
            category == null -> CategoryColors.setNoCategoryColor(context, color)
            reset -> CategoryColors.clearCategoryColor(context, category)
            // Don't turn a factory color into a custom one when OK is pressed without a change
            colorChanged || isNewCategory || CategoryColors.isCustomized(context, category) ->
                CategoryColors.setCategoryColor(context, category, color)
        }
        Utils.sendUpdateWidgetIntent(context)
        refresh()
    }

    companion object {
        private const val DIALOG_TAG = "category_color_dialog"
        private const val NAME_DIALOG_TAG = "category_name_dialog"
        private const val STATE_PENDING_NEW_CATEGORY = "pending_new_category"
    }
}
