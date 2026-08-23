package com.caniko.cenix

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneGridTest {
    @Test
    fun smallestFallsBackToTwoByTwo() {
        assertEquals("2_by_2", PhoneGrid.pick(100f, 100f).name)
        assertEquals("2_by_2", PhoneGrid.pick(200f, 200f).name)
    }

    @Test
    fun emulatorClassPicksFourByFour() {
        assertEquals("4_by_4", PhoneGrid.pick(320f, 640f).name)
    }

    @Test
    fun tallPixelClassPicksFourByFive() {
        assertEquals("4_by_5", PhoneGrid.pick(367f, 838f).name)
    }

    @Test
    fun largePhonePicksFiveByFive() {
        assertEquals("5_by_5", PhoneGrid.pick(406f, 838f).name)
    }

    @Test
    fun threeByThreeThreshold() {
        assertEquals("3_by_3", PhoneGrid.pick(255f, 300f).name)
    }
}
