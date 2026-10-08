package com.android.calendar.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CategoryColorDialogTest {

    @Test
    public void parseHexColor() {
        assertEquals(Integer.valueOf(0xFFFFCC00), CategoryColorDialog.Companion.parseHexColor("#ffcc00"));
        assertEquals(Integer.valueOf(0xFF003333), CategoryColorDialog.Companion.parseHexColor("003333"));
        assertNull(CategoryColorDialog.Companion.parseHexColor("#FFF"));
        assertNull(CategoryColorDialog.Companion.parseHexColor("#GGGGGG"));
        assertEquals("#FFCC00", CategoryColorDialog.Companion.formatHexColor(0xFFFFCC00));
    }
}
