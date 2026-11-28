package com.nirowear.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTextSizeTest {
    @Test
    fun nextCyclesCompactStandardLargeBackToCompact() {
        assertEquals(ReaderTextSize.STANDARD, ReaderTextSize.COMPACT.next())
        assertEquals(ReaderTextSize.LARGE, ReaderTextSize.STANDARD.next())
        assertEquals(ReaderTextSize.COMPACT, ReaderTextSize.LARGE.next())
    }

    @Test
    fun scalesIncreaseMonotonicallyAroundStandard() {
        assertEquals(0.88f, ReaderTextSize.COMPACT.scale, 0.0001f)
        assertEquals(1.0f, ReaderTextSize.STANDARD.scale, 0.0001f)
        assertEquals(1.15f, ReaderTextSize.LARGE.scale, 0.0001f)
    }

    @Test
    fun fromStoredFallsBackToStandardForNullOrUnknownValues() {
        assertEquals(ReaderTextSize.STANDARD, ReaderTextSize.fromStored(null))
        assertEquals(ReaderTextSize.STANDARD, ReaderTextSize.fromStored(""))
        assertEquals(ReaderTextSize.STANDARD, ReaderTextSize.fromStored("HUGE"))
        assertEquals(ReaderTextSize.COMPACT, ReaderTextSize.fromStored("COMPACT"))
        assertEquals(ReaderTextSize.LARGE, ReaderTextSize.fromStored("LARGE"))
    }
}
