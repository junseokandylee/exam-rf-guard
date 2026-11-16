package com.classroom.rfdetector.sim

import com.classroom.rfdetector.core.RfObservation
import com.classroom.rfdetector.core.ScanSource
import java.util.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * 에뮬레이터/실기기 검증용 가상 RF 환경.
 * 실제 스캐너와 동일한 관측 형식(RfObservation)을 만들어 같은 파이프라인을 탄다.
 * 좌표계: 북=+y, 동=+x. 사용자는 (userX, userY, userHeadingDeg) 로 움직인다.
 */
class RadioSimulator {
    class VDev(
        val key: String,
        val name: String,
        val source: ScanSource,
        val hidden: Boolean,
        var x: Double,
        var y: Double,
        val txDbm: Double = -59.0,
        val frequencyMhz: Int? = null,
    )

    val devices = mutableListOf(
        VDev("sim-airpods", "AirPods Pro (가상)", ScanSource.BLE, false, x = 1.3, y = 2.6),
        VDev("sim-buds", "Galaxy Buds (가상)", ScanSource.BLE, false, x = -2.6, y = 1.4),
        VDev("sim-watch", "스마트워치 (가상)", ScanSource.BLE, false, x = 0.5, y = 0.9),
        VDev("sim-hotspot", "휴대폰 핫스팟 (가상)", ScanSource.WIFI, false, x = 3.8, y = -4.2, frequencyMhz = 2437),
        VDev("sim-hidden", "(이름 없는 AP · 가상)", ScanSource.WIFI, true, x = -4.6, y = -3.2, frequencyMhz = 5180),
    )

    var userX = 0.0
    var userY = 0.0
    var userHeadingDeg = 0.0

    private val rnd = Random()

    fun reset() {
        devices.removeAll { it.key.startsWith("sim-extra-") }
        userX = 0.0
        userY = 0.0
        userHeadingDeg = 0.0
    }

    fun addDevice() {
        val index = devices.count { it.source == ScanSource.BLE && it.key.startsWith("sim-extra-") } + 1
        devices.add(VDev("sim-extra-$index", "AirPods Pro · 추가 가상 $index", ScanSource.BLE, false, userX + 1.0, userY + 1.0))
    }

    /** 사용자가 현재 방향 기준 (right, forward) 미터 이동 */
    fun moveUser(rightM: Double, forwardM: Double) {
        val h = Math.toRadians(userHeadingDeg)
        userX += rightM * cos(h) + forwardM * sin(h)
        userY += -rightM * sin(h) + forwardM * cos(h)
    }

    fun rotateUser(deltaDeg: Double) {
        userHeadingDeg = (userHeadingDeg + deltaDeg).mod(360.0)
    }

    /** 4Hz 정도로 호출: 현재 사용자 위치 기준 관측값 생성 */
    fun tick(): List<RfObservation> {
        val now = System.currentTimeMillis()
        val h = Math.toRadians(userHeadingDeg)
        return devices.map { d ->
            val dx = d.x - userX
            val dy = d.y - userY
            val fwd = dx * sin(h) + dy * cos(h)
            val right = dx * cos(h) - dy * sin(h)
            val dist = max(0.4, hypot(right, fwd))
            var rssi = d.txDbm - 10 * 2.7 * log10(dist) + rnd.nextGaussian() * 3.2
            if (rnd.nextDouble() < 0.08) rssi -= 5.0   // 다중경로 페이딩 흉내
            RfObservation(
                key = d.key,
                source = d.source,
                name = d.name.takeIf { !d.hidden },
                rssi = rssi.toInt(),
                txPower = null,
                hidden = d.hidden,
                timestampMs = now,
                frequencyMhz = d.frequencyMhz,
                manufacturerIds = when {
                    d.source == ScanSource.BLE && (d.key == "sim-airpods" || d.key.startsWith("sim-extra-")) -> setOf(76)
                    d.source == ScanSource.BLE && d.key == "sim-buds" -> setOf(117)
                    else -> emptySet()
                },
            )
        }
    }
}
