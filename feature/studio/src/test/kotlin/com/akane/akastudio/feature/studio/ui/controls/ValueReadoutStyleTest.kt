package com.akane.akastudio.feature.studio.ui.controls

import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class ValueReadoutStyleTest {

    /**
     * A signed readout such as `+50` has no strong-direction character, so under an RTL layout it inherits an RTL
     * paragraph and the bidi algorithm draws it as `50+`. The readout resolves its paragraph direction from its own
     * content and falls back to LTR, so digits and signs keep their order while a translated label that starts
     * with an RTL letter still reads right to left.
     */
    @Test
    fun `readouts take their direction from content and fall back to left to right`() {
        assertEquals(TextDirection.ContentOrLtr, ValueReadoutStyle.textDirection)
    }
}
