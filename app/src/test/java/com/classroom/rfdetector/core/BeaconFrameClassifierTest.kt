package com.classroom.rfdetector.core

import org.junit.Assert.*
import org.junit.Test

class BeaconFrameClassifierTest {
    private val service = "0000feaa-0000-1000-8000-00805f9b34fb"
    private fun frame(size: Int, vararg header: Int) = ByteArray(size).apply {
        header.forEachIndexed { index, value -> this[index] = value.toByte() }
    }
    private fun formats(manufacturer: Map<Int, ByteArray> = emptyMap(), services: Map<String, ByteArray> = emptyMap()) =
        identifyBeaconFrames(manufacturer, services).map { it.format }
    private fun eddystone(payload: ByteArray) = formats(services = mapOf(service to payload))

    @Test fun unnamedIBeaconAndAltBeaconMatchPayloadWithoutAProductName() {
        assertEquals(listOf(BeaconFormat.IBEACON_COMPATIBLE), formats(mapOf(0x004c to frame(23, 0x02, 0x15))))
        assertEquals(listOf(BeaconFormat.ALTBEACON), formats(mapOf(0x0118 to frame(24, 0xbe, 0xac))))
        assertEquals(listOf(BeaconFormat.ALTBEACON), formats(mapOf(0x004c to frame(24, 0xbe, 0xac))))
    }

    @Test fun companyCodeOrServiceAloneDoesNotClaimABeacon() {
        assertTrue(formats(mapOf(0x004c to byteArrayOf())).isEmpty())
        assertTrue(formats(mapOf(0x1234 to frame(23, 0x02, 0x15))).isEmpty())
        assertTrue(eddystone(byteArrayOf()).isEmpty())
        assertTrue(formats(services = mapOf("0000180f-0000-1000-8000-00805f9b34fb" to frame(20))).isEmpty())
        assertTrue(formats(mapOf(-1 to frame(24, 0xbe, 0xac))).isEmpty())
    }

    @Test fun truncatedAndExtendedManufacturerFramesRemainUnidentified() {
        listOf(0, 1, 2, 22, 24, 30).forEach { size ->
            val data = ByteArray(size).apply { if (size > 0) this[0] = 2; if (size > 1) this[1] = 0x15 }
            assertTrue("iBeacon length $size", formats(mapOf(0x004c to data)).isEmpty())
        }
        assertTrue(formats(mapOf(0x0118 to frame(23, 0xbe, 0xac))).isEmpty())
        assertTrue(formats(mapOf(0x0118 to frame(25, 0xbe, 0xac))).isEmpty())
    }

    @Test fun allSupportedEddystoneFramesHaveDistinctLabelsAndLengths() {
        assertEquals(listOf(BeaconFormat.EDDYSTONE_UID), eddystone(frame(20, 0x00, 0xee)))
        assertEquals(listOf(BeaconFormat.EDDYSTONE_UID), eddystone(frame(18, 0x00, 0xee)))
        assertEquals(listOf(BeaconFormat.EDDYSTONE_URL), eddystone(frame(5, 0x10, 0xee, 3, 'x'.code, 7)))
        assertEquals(listOf(BeaconFormat.EDDYSTONE_TLM), eddystone(frame(14, 0x20, 0)))
        assertEquals(listOf(BeaconFormat.EDDYSTONE_ETLM), eddystone(frame(18, 0x20, 1)))
        assertEquals(listOf(BeaconFormat.EDDYSTONE_EID), eddystone(frame(10, 0x30, 0xee)))
        assertEquals(listOf(BeaconFormat.EDDYSTONE_UID), formats(services = mapOf(service.uppercase() to frame(20))))
    }

    @Test fun malformedOrUnsupportedEddystoneDoesNotReceiveKnownLabel() {
        val malformed = listOf(
            frame(19, 0x00), frame(20, 0x00).apply { this[19] = 1 }, frame(20, 0x01),
            frame(3, 0x10, 0, 0), frame(21, 0x10, 0, 0), frame(4, 0x10, 0, 4, 7),
            frame(4, 0x10, 0, 0, 14), frame(4, 0x10, 0, 0, 0xff),
            frame(14, 0x20, 1), frame(18, 0x20, 0), frame(14, 0x20, 2),
            frame(9, 0x30), frame(11, 0x30), frame(10, 0x31), frame(10, 0x40),
        )
        malformed.forEach { assertTrue("Unexpected format for ${it.joinToString()}", eddystone(it).isEmpty()) }
    }

    @Test fun multipleSectionsKeepDistinctFormatsAndDoNotMutateInput() {
        val alt = frame(24, 0xbe, 0xac)
        val original = alt.copyOf()
        val result = identifyBeaconFrames(
            linkedMapOf(0x004c to frame(23, 2, 0x15), 0x0118 to alt, 0x1234 to alt),
            mapOf(service to frame(14, 0x20, 0)),
        )
        assertEquals(listOf(BeaconFormat.IBEACON_COMPATIBLE, BeaconFormat.ALTBEACON, BeaconFormat.EDDYSTONE_TLM), result.map { it.format })
        assertEquals(listOf(23, 24, 14), result.map { it.payloadBytes })
        assertArrayEquals(original, alt)
    }
}
