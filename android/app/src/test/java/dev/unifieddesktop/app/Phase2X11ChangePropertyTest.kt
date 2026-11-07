package dev.unifieddesktop.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B-130: the X11 ChangeProperty request's data-length field counts ITEMS in the property
 * format (bytes for 8, longs for 32), not bytes. Writing the byte count made every
 * format=32 reply we served to wine (the TARGETS atom list) claim 4x more data than the
 * request carried, and the clipboard conversion dropped it.
 */
class Phase2X11ChangePropertyTest {

    @Test
    fun aFourAtomTargetsReplyHasLengthFourForFormat32() {
        // The bridge's TARGETS reply: 4 atoms x 4 bytes = 16 bytes = 4 items.
        assertEquals(4, Phase2X11.ChangePropertyWire.lengthUnits(16, 32))
    }

    @Test
    fun aTimestampReplyHasLengthOneForFormat32() {
        // The TIMESTAMP/integer reply: one 4-byte value.
        assertEquals(1, Phase2X11.ChangePropertyWire.lengthUnits(4, 32))
    }

    @Test
    fun aTenByteStringReplyHasLengthTenForFormat8() {
        // Text replies stay byte counts (format 8): the 10-byte STRING served to wine.
        assertEquals(10, Phase2X11.ChangePropertyWire.lengthUnits(10, 8))
    }

    @Test
    fun aFormat16ReplyCountsTwoBytesPerItem() {
        assertEquals(3, Phase2X11.ChangePropertyWire.lengthUnits(6, 16))
    }

    @Test
    fun emptyDataHasZeroItemsInAnyFormat() {
        assertEquals(0, Phase2X11.ChangePropertyWire.lengthUnits(0, 8))
        assertEquals(0, Phase2X11.ChangePropertyWire.lengthUnits(0, 32))
    }

    @Test
    fun requestLengthIsSixFixedWordsPlusTheDataWords() {
        // 16 bytes of data -> 4 data words -> 10 total words (24-byte fixed part + 16 data).
        assertEquals(10, Phase2X11.ChangePropertyWire.requestWords(16))
        // 10 bytes -> 3 padded data words -> 9 total.
        assertEquals(9, Phase2X11.ChangePropertyWire.requestWords(10))
        // 0 bytes -> 6 total.
        assertEquals(6, Phase2X11.ChangePropertyWire.requestWords(0))
    }
}
