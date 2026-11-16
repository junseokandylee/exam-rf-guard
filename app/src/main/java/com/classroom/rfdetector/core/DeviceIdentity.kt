package com.classroom.rfdetector.core

/** Every identity field retains its source; advertising names and IDs are self-reported. */
data class DeviceIdentity(
    val advertisedCompanies: List<String> = emptyList(),
    val macOrganization: String? = null,
    val modelHint: String? = null,
    val addressNote: String = "",
    val audioNameMatch: AudioNameMatch? = null,
    val evidenceNote: String? = null,
    val wearableNameMatch: WearableNameMatch? = null,
) {
    val summary: String get() = advertisedCompanies.firstOrNull()?.let { "$it · BLE 광고" }
        ?: macOrganization?.let { "$it · MAC 등록사" }
        ?: audioNameMatch?.let { "${it.brand} · 이름 기반 후보" }
        ?: wearableNameMatch?.let { "${it.brand} · 이름 기반 후보" } ?: "제조사 미확인"

    fun matchesQuery(query: String, name: String, address: String): Boolean = fieldsMatchQuery(query,
        listOfNotNull(name, address, modelHint, macOrganization, audioNameMatch?.brand, audioNameMatch?.searchModel,
            wearableNameMatch?.brand, wearableNameMatch?.model, wearableNameMatch?.kind?.label) + advertisedCompanies +
            (audioNameMatch?.brandAliases ?: emptyList()) + (wearableNameMatch?.aliases ?: emptyList()))
}

class DeviceIdentityResolver(
    private val macVendors: Map<String, String>,
    private val bluetoothCompanies: Map<Int, String>,
) {
    private val macPattern = Regex("[0-9A-F]{12}")

    fun resolve(address: String, name: String?, sources: Set<ScanSource>, manufacturerIds: Set<Int>): DeviceIdentity {
        val mac = address.replace(":", "").replace("-", "").uppercase()
        val validMac = mac.matches(macPattern)
        val firstOctet = if (validMac) mac.take(2).toInt(16) else 0
        val local = validMac && firstOctet and 2 != 0
        val unicast = validMac && firstOctet and 1 == 0
        val organization = if (unicast && !local) macVendors[mac.take(6)] else null
        val note = when {
            !validMac -> "가상 또는 비표준 주소 · MAC 조회 대상 아님"
            local -> "로컬 관리 주소 · 임의 주소일 수 있어 MAC 제조사 조회 생략"
            ScanSource.BLE in sources -> "BLE 주소는 임의 주소일 수 있습니다. MAC 등록사만으로 제조사를 확정할 수 없습니다."
            else -> "MAC 등록사는 네트워크 인터페이스 등록 조직이며 제품 브랜드·모델과 다를 수 있습니다."
        }
        val orderedIds = manufacturerIds.sortedWith(compareBy<Int> { it !in bluetoothCompanies }.thenBy { it })
        val companies = orderedIds.map { bluetoothCompanies[it] ?: "미등록 회사 ID 0x${it.toString(16).uppercase().padStart(4, '0')}" }
        val audio = if (sources.any { it.isBluetooth }) AudioNameCatalog.match(name) else null
        val wearable = if (sources.any { it.isBluetooth }) WearableNameCatalog.match(name) else null
        val mismatch = audioEvidenceNote(audio, manufacturerIds)
        return DeviceIdentity(companies, organization, audio?.model ?: wearable?.model, note, audio, mismatch, wearable)
    }
}
