package com.classroom.rfdetector.sim

import com.classroom.rfdetector.core.ScanSource
import org.junit.Assert.*
import org.junit.Test

class RadioSimulatorTest {
    @Test fun wifiObservationsCarryTheirFrequencyWhileBleDoesNot() {
        val simulator = RadioSimulator()
        val samples = simulator.tick()
        val wifi = samples.filter { it.source == ScanSource.WIFI }
        assertEquals(listOf(2437, 5180), wifi.map { it.frequencyMhz })
        wifi.forEach { sample ->
            assertEquals(simulator.devices.single { it.key == sample.key }.frequencyMhz, sample.frequencyMhz)
        }
        assertTrue(samples.filter { it.source == ScanSource.BLE }.all { it.frequencyMhz == null })
    }

    @Test fun addedWifiHasNoBleCompanyCodeAndDoesNotConsumeEarbudNumbering() {
        val simulator = RadioSimulator()
        simulator.devices.add(RadioSimulator.VDev("sim-extra-wifi-1", "hotspot", ScanSource.WIFI,
            false, 1.0, 1.0, frequencyMhz = 2462))
        simulator.addDevice()
        val samples = simulator.tick()
        assertTrue(samples.single { it.key == "sim-extra-wifi-1" }.manufacturerIds.isEmpty())
        assertEquals(setOf(76), samples.single { it.key == "sim-extra-1" }.manufacturerIds)
        simulator.reset()
        assertTrue(simulator.devices.none { it.key.startsWith("sim-extra-") })
    }
}
