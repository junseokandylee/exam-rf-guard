package com.classroom.rfdetector.core

/** An observed payload layout, never a verified hardware type, manufacturer or beacon identity. */
enum class BeaconFormat(val label: String) {
    IBEACON_COMPATIBLE("iBeacon 호환 형식"),
    ALTBEACON("AltBeacon 형식"),
    EDDYSTONE_UID("Eddystone UID"),
    EDDYSTONE_URL("Eddystone URL"),
    EDDYSTONE_TLM("Eddystone TLM"),
    EDDYSTONE_ETLM("Eddystone 암호화 TLM"),
    EDDYSTONE_EID("Eddystone EID"),
}

data class BeaconFrameHint(val format: BeaconFormat, val payloadBytes: Int)

const val BEACON_FORMAT_NOTE = "광고 데이터가 알려진 비콘 형식과 일치합니다. 전용 비콘 하드웨어·제조사·모델을 확인한 결과가 아닙니다. iBeacon의 0x004C는 Apple 하드웨어, Eddystone의 FEAA는 Google 하드웨어를 뜻하지 않습니다."

private const val EDDYSTONE_SERVICE = "0000feaa-0000-1000-8000-00805f9b34fb"

/**
 * Inputs follow Android ScanRecord: company IDs and service UUIDs are map keys, not payload bytes.
 * Unknown/truncated advertisements deliberately return no hint; callers must still retain the event.
 * No advertised identifiers are used to join addresses, URLs are not opened, and reference power
 * is not treated as a 1 m distance calibration (Eddystone's reference is at 0 m).
 * Layout sources: github.com/AltBeacon/spec and github.com/google/eddystone.
 * iBeacon layout: Silicon Labs, Developing Beacons with Bluetooth Low Energy Technology, p. 21.
 */
fun identifyBeaconFrames(
    manufacturerData: Map<Int, ByteArray>,
    serviceData: Map<String, ByteArray>,
): List<BeaconFrameHint> = buildList {
    manufacturerData.forEach { (company, bytes) ->
        if (company !in 0..0xffff) return@forEach
        when {
            company == 0x004c && bytes.size == 23 && bytes.u8(0) == 0x02 && bytes.u8(1) == 0x15 ->
                add(BeaconFrameHint(BeaconFormat.IBEACON_COMPATIBLE, bytes.size))
            bytes.size == 24 && bytes.u8(0) == 0xbe && bytes.u8(1) == 0xac ->
                add(BeaconFrameHint(BeaconFormat.ALTBEACON, bytes.size))
        }
    }
    serviceData.forEach { (uuid, bytes) ->
        if (!uuid.equals(EDDYSTONE_SERVICE, ignoreCase = true)) return@forEach
        eddystoneFormat(bytes)?.let { add(BeaconFrameHint(it, bytes.size)) }
    }
}.distinctBy { it.format }

private fun eddystoneFormat(bytes: ByteArray): BeaconFormat? {
    if (bytes.isEmpty()) return null
    return when (bytes.u8(0)) {
        // The original specification explicitly permits older UID frames omitting the two RFU bytes.
        0x00 -> if (bytes.size == 18 || (bytes.size == 20 && bytes.u8(18) == 0 && bytes.u8(19) == 0))
            BeaconFormat.EDDYSTONE_UID else null
        0x10 -> if (bytes.size in 4..20 && bytes.u8(2) in 0..3 &&
            (3 until bytes.size).all { bytes.u8(it) in 0..13 || bytes.u8(it) in 0x21..0x7e })
            BeaconFormat.EDDYSTONE_URL else null
        0x20 -> when {
            bytes.size == 14 && bytes.u8(1) == 0 -> BeaconFormat.EDDYSTONE_TLM
            bytes.size == 18 && bytes.u8(1) == 1 -> BeaconFormat.EDDYSTONE_ETLM
            else -> null
        }
        0x30 -> if (bytes.size == 10) BeaconFormat.EDDYSTONE_EID else null
        else -> null
    }
}

private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xff
