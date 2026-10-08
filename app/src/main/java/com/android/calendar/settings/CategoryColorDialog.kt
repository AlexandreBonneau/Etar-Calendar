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

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.DialogFragment
import com.android.calendar.colorpicker.ColorPickerPalette
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ws.xsoh.etar.R
import java.util.Locale

/**
 * Lets the user pick the color of a category (or of events without any category): palette of
 * predefined colors, hexadecimal input and a live preview with the WCAG-contrasted text.
 * The result is delivered through the parent FragmentManager under [REQUEST_KEY], so it survives
 * configuration changes.
 */
class CategoryColorDialog : DialogFragment() {

    private var selectedColor: Int = Color.GRAY
    private var updatingHex = false
    /** Color currently highlighted in the palette, to redraw it only when that changes. */
    private var paletteSelection: Int? = null

    private lateinit var palette: ColorPickerPalette
    private lateinit var hexInput: EditText
    private lateinit var preview: TextView

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        selectedColor = savedInstanceState?.getInt(STATE_COLOR)?: args.getInt(ARG_COLOR, Color.GRAY)
        val category = args.getString(ARG_CATEGORY)

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.category_color_dialog, null)
        preview = view.findViewById(R.id.category_color_preview)
        hexInput = view.findViewById(R.id.category_color_hex)
        palette = view.findViewById(R.id.color_picker)
        // Compact swatches, to keep the dialog short vertically.
        palette.init(resources.getDimensionPixelSize(R.dimen.category_color_swatch),
            resources.getDimensionPixelSize(R.dimen.category_color_swatch_margin),
            PALETTE_COLUMNS) { color ->
            selectColor(color, updateHex = true)
        }

        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingHex) return
                parseHexColor(s?.toString())?.let {
                    selectColor(it, updateHex = false)
                }
            }
        })

        selectColor(selectedColor, updateHex = true)

        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(category ?: getString(R.string.preferences_category_colors_no_category))
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                parentFragmentManager.setFragmentResult(REQUEST_KEY, Bundle().apply {
                    putString(RESULT_CATEGORY, category)
                    putInt(RESULT_COLOR, selectedColor)
                    putBoolean(RESULT_COLOR_CHANGED, selectedColor != args.getInt(ARG_COLOR))
                    putBoolean(RESULT_RESET, false)
                })
            }
            .setNegativeButton(android.R.string.cancel, null)
        // "Default" removes the color chosen by the user. Always shown so that the option is
        // discoverable, but disabled when there is nothing to reset
        builder.setNeutralButton(R.string.category_color_dialog_reset) { _, _ ->
            parentFragmentManager.setFragmentResult(REQUEST_KEY, Bundle().apply {
                putString(RESULT_CATEGORY, category)
                putBoolean(RESULT_RESET, true)
            })
        }
        val dialog = builder.create()
        val canReset = args.getBoolean(ARG_ALLOW_RESET)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = canReset
        }
        return dialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_COLOR, selectedColor)
    }

    private fun selectColor(color: Int, updateHex: Boolean) {
        selectedColor = ColorUtils.setAlphaComponent(color, 0xFF)
        // Rebuilding the palette is costly: only do it when its highlighted swatch changes
        val newSelection = selectedColor.takeIf { it in PALETTE }
        if (newSelection != paletteSelection || palette.childCount == 0) {
            paletteSelection = newSelection
            palette.drawPalette(PALETTE, selectedColor)
        }
        preview.setBackgroundColor(selectedColor)
        preview.setTextColor(contrastedTextColor(selectedColor))
        if (updateHex) {
            updatingHex = true
            hexInput.setText(formatHexColor(selectedColor))
            hexInput.setSelection(hexInput.text.length)
            updatingHex = false
        }
    }

    companion object {
        const val REQUEST_KEY = "category_color_request"
        /** Category name, or null for the "no category" color. */
        const val RESULT_CATEGORY = "category"
        const val RESULT_COLOR = "color"
        /** True when the user asked to remove the configured color. */
        const val RESULT_RESET = "reset"
        /** True when the color differs from the one the dialog was opened with. */
        const val RESULT_COLOR_CHANGED = "color_changed"

        private const val ARG_CATEGORY = "category"
        private const val ARG_COLOR = "color"
        private const val ARG_ALLOW_RESET = "allow_reset"
        private const val STATE_COLOR = "selected_color"
        private const val PALETTE_COLUMNS = 7

        /** Predefined colors (Material palette), light and dark tones mixed. */
        val PALETTE = intArrayOf(
            0xFFD50000.toInt(), 0xFFE67C73.toInt(), 0xFFF4511E.toInt(), 0xFFF6BF26.toInt(),
            0xFFFFF176.toInt(), 0xFF33B679.toInt(), 0xFF0B8043.toInt(), 0xFFAED581.toInt(),
            0xFF039BE5.toInt(), 0xFF3F51B5.toInt(), 0xFF81D4FA.toInt(), 0xFF7986CB.toInt(),
            0xFF8E24AA.toInt(), 0xFFCE93D8.toInt(), 0xFFF06292.toInt(), 0xFF795548.toInt(),
            0xFFBCAAA4.toInt(), 0xFF616161.toInt(), 0xFFBDBDBD.toInt(), 0xFF78909C.toInt()
        )

        @JvmStatic
        @JvmOverloads
        fun newInstance(category: String?, color: Int, allowReset: Boolean) =
            CategoryColorDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_CATEGORY, category)
                    putInt(ARG_COLOR, color)
                    putBoolean(ARG_ALLOW_RESET, allowReset)
                }
            }

        /** Parses "#RRGGBB" or "RRGGBB", and returns `null` while the input is incomplete/invalid. */
        fun parseHexColor(text: String?): Int? {
            val hex = text?.trim()?.removePrefix("#") ?: return null
            if (hex.length != 6) return null
            return hex.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
        }

        fun formatHexColor(color: Int): String =
            String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

        /** Black or white, whichever has the best WCAG contrast ratio on `background` */
        fun contrastedTextColor(background: Int): Int {
            val opaque = ColorUtils.setAlphaComponent(background, 0xFF)
            return if (ColorUtils.calculateContrast(Color.BLACK, opaque) >
                ColorUtils.calculateContrast(Color.WHITE, opaque)) Color.BLACK else Color.WHITE
        }
    }
}
