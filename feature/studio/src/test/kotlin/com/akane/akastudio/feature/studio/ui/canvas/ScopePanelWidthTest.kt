package com.akane.akastudio.feature.studio.ui.canvas

import androidx.compose.ui.unit.dp
import com.akane.akastudio.feature.studio.ui.canvas.scope.ScopeMinWidth
import org.junit.Assert.assertEquals
import org.junit.Test

/** SQ-65: the docked scope was 40% of the frame, about 78 dp on the device; it now has a readable floor. */
class ScopePanelWidthTest {

    private val inset = 8.dp

    @Test
    fun `a narrow frame lifts the scope to the minimum width`() {
        assertEquals(ScopeMinWidth, scopePanelWidth(195.dp, inset))
    }

    @Test
    fun `a wide frame keeps 40 percent`() {
        assertEquals(200.dp, scopePanelWidth(500.dp, inset))
    }

    @Test
    fun `a frame narrower than the minimum caps the scope inside its insets`() {
        assertEquals(104.dp, scopePanelWidth(120.dp, inset))
    }

    @Test
    fun `a frame no wider than its insets gives a zero-width scope`() {
        assertEquals(0.dp, scopePanelWidth(10.dp, inset))
        assertEquals(0.dp, scopePanelWidth(0.dp, inset))
    }
}
