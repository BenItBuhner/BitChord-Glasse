package com.benitbuhner.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The content-height MenuModel behind GlassMenuPopover: the same morph as the item menus, opening to the
 * measured content height and draining back into the trigger.
 */
class MenuPopoverModelTest {
    private fun run(m: MenuModel, open: Boolean): Int {
        m.show(open)
        var n = 0
        while (m.step(1.0 / 120) && n < 2400) n++
        return n
    }

    @Test fun opensToTheContentHeightAndClosesIntoTheTrigger() {
        for (v in MotionVersion.entries) {
            val m = MenuModel(contentHeight = 312f, width = 256f, alignRight = true, dark = true)
            m.nextMotion = MotionPresets.of(v)
            assertEquals(312f, m.Hm)
            assertTrue(m.rows.isEmpty())
            assertTrue("$v open settles", run(m, true) < 2400)
            val b = m.body()
            assertEquals("$v open height", 312f, b.h, 1f)
            assertEquals("$v open width", 256f, b.w, 1f)
            assertTrue("$v close settles", run(m, false) < 2400)
            assertEquals(MenuModel.Phase.Closed, m.phase)
            assertEquals(1f, m.restHandoff())
        }
    }

    @Test fun noRowsMeansNoPressDragSelection() {
        val m = MenuModel(contentHeight = 200f, width = 250f, alignRight = false, dark = false)
        run(m, true)
        m.listDown(40f, 60f)
        assertEquals(-1, m.listUp(60f))
        assertFalse(m.rows.any { !it.separator })
    }

    @Test fun aShortContentStillOpensPastTheTrigger() {
        assertEquals(44f, MenuModel(contentHeight = 10f, width = 250f, alignRight = true, dark = false).Hm)
    }
}
