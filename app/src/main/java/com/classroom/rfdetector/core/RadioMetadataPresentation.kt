package com.classroom.rfdetector.core

/** These are advertised/API-provided fields, not verified device capabilities or risk scores. */
fun RadioMetadata.detailFields(): List<Pair<String, String>> = buildList {
    wifi?.let { addAll(it.detailFields()) }
    ble?.let { metadata ->
        if (metadata.beaconFormats.isNotEmpty()) {
            add("관측된 비콘 광고 형식" to metadata.beaconFormats.distinct().joinToString(" · ") { it.label })
            add("비콘 형식 해석" to BEACON_FORMAT_NOTE)
        }
        metadata.legacy?.let { add("광고 형식" to if (it) "기본 BLE 광고" else "확장 BLE 광고") }
        metadata.connectable?.let { add("연결 가능 광고" to if (it) "예 · 실제 연결하지 않음" else "아니요 · 실제 연결 상태 아님") }
        metadata.primaryPhy?.let { add("기본 PHY" to phyName(it)) }
        metadata.secondaryPhy?.let { add("보조 PHY" to phyName(it)) }
        add("광고 서비스 UUID" to metadata.serviceUuids.joinToString("\n").ifEmpty { "제공되지 않음" })
    }
    classicDeviceClass?.let { add("Bluetooth 기기 클래스" to "0x${it.toString(16).uppercase(java.util.Locale.ROOT).padStart(6, '0')} · 기기에서 알린 값") }
}

fun WifiRadioMetadata.detailFields(): List<Pair<String, String>> = buildList {
    channelWidthLabel?.let { add("채널 폭" to it) }
    centerFrequency0Mhz?.let { add("중심 주파수 1" to "$it MHz") }
    centerFrequency1Mhz?.let { add("중심 주파수 2" to "$it MHz") }
    capabilities?.let { add("보안·기능 광고" to it) }
}

private fun phyName(value: Int): String = when (value) {
    0 -> "없음"
    1 -> "LE 1M"
    2 -> "LE 2M"
    3 -> "LE Coded"
    else -> "API 값 $value"
}
