package com.classroom.rfdetector.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class RfSymbol(val label: String) {
    BLUETOOTH("Bluetooth"), WIFI("Wi-Fi"), HIDDEN("SSID 미표시"), EARPHONES("이어폰"), HEADPHONES("헤드폰"),
    GLASSES("스마트 안경"), WATCH("스마트워치"), CAMERA("카메라"),
    CLOCK("관측 시간"), RETURN("재등장"), UNION("합집합"), DISTANCE("예상 거리"),
    COMPANY("제조사"), MODEL("모델"), INFO("자세한 설명"), SNAPSHOT("스냅샷 저장"), CHECK("완료")
}

/** Small code-native glyphs keep radio, time and reappearance cues consistent and offline. */
@Composable
internal fun RfGlyph(symbol: RfSymbol, size: Dp = 20.dp, color: Color = RfColors.primary) {
    Canvas(Modifier.size(size).semantics { contentDescription = symbol.label }) {
        scale(this.size.width / 24f, this.size.height / 24f, pivot = Offset.Zero) {
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1, y1), Offset(x2, y2), 1.8f, StrokeCap.Round)
            fun path(vararg points: Pair<Float, Float>) {
                val path = Path()
                points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.first, p.second) else path.lineTo(p.first, p.second) }
                drawPath(path, color, style = Stroke(1.8f, cap = StrokeCap.Round))
            }
            when (symbol) {
                RfSymbol.BLUETOOTH -> { path(7f to 7f, 17f to 16f, 12f to 21f, 12f to 3f, 17f to 8f, 7f to 17f) }
                RfSymbol.WIFI -> { drawArc(color, 222f, 96f, false, Offset(2f, 4f), Size(20f, 20f), style = Stroke(1.8f)); drawArc(color, 222f, 96f, false, Offset(6f, 9f), Size(12f, 12f), style = Stroke(1.8f)); drawCircle(color, 1.5f, Offset(12f, 19f)) }
                RfSymbol.HIDDEN -> { path(2f to 12f, 6f to 7f, 12f to 5f, 18f to 7f, 22f to 12f, 18f to 17f, 12f to 19f, 6f to 17f, 2f to 12f); drawCircle(color, 3f, Offset(12f, 12f), style = Stroke(1.8f)); line(3f, 3f, 21f, 21f) }
                RfSymbol.EARPHONES -> { drawCircle(color, 3f, Offset(6f, 7f), style = Stroke(1.8f)); drawCircle(color, 3f, Offset(18f, 7f), style = Stroke(1.8f)); line(8f, 9f, 8f, 20f); line(16f, 9f, 16f, 20f) }
                RfSymbol.HEADPHONES -> { drawArc(color, 180f, 180f, false, Offset(3f, 3f), Size(18f, 18f), style = Stroke(1.8f)); line(3f, 12f, 3f, 20f); line(21f, 12f, 21f, 20f); line(6f, 13f, 6f, 20f); line(18f, 13f, 18f, 20f) }
                RfSymbol.GLASSES -> { drawCircle(color, 4f, Offset(6f, 14f), style = Stroke(1.8f)); drawCircle(color, 4f, Offset(18f, 14f), style = Stroke(1.8f)); line(10f, 13f, 14f, 13f); path(2f to 12f, 4f to 5f, 7f to 5f); path(22f to 12f, 20f to 5f, 17f to 5f) }
                RfSymbol.WATCH -> { drawRoundRect(color, Offset(6f, 7f), Size(12f, 10f), androidx.compose.ui.geometry.CornerRadius(3f, 3f), style = Stroke(1.8f)); path(9f to 7f, 9f to 2f, 15f to 2f, 15f to 7f); path(9f to 17f, 9f to 22f, 15f to 22f, 15f to 17f); path(12f to 9f, 12f to 12f, 15f to 13f) }
                RfSymbol.CAMERA -> { path(2f to 7f, 8f to 7f, 10f to 4f, 15f to 4f, 17f to 7f, 22f to 7f, 22f to 20f, 2f to 20f, 2f to 7f); drawCircle(color, 4f, Offset(12f, 13f), style = Stroke(1.8f)); drawCircle(color, 1f, Offset(19f, 10f)) }
                RfSymbol.CLOCK -> { drawCircle(color, 9f, Offset(12f, 12f), style = Stroke(1.8f)); path(12f to 6f, 12f to 12f, 17f to 15f) }
                RfSymbol.RETURN -> { drawArc(color, 215f, 290f, false, Offset(4f, 4f), Size(16f, 16f), style = Stroke(1.8f)); path(3f to 4f, 3f to 10f, 9f to 10f) }
                RfSymbol.UNION -> { drawCircle(color, 7f, Offset(8f, 12f), style = Stroke(1.8f)); drawCircle(color, 7f, Offset(16f, 12f), style = Stroke(1.8f)) }
                RfSymbol.DISTANCE -> { line(3f, 12f, 21f, 12f); path(7f to 8f, 3f to 12f, 7f to 16f); path(17f to 8f, 21f to 12f, 17f to 16f) }
                RfSymbol.COMPANY -> { path(3f to 21f, 3f to 10f, 10f to 6f, 10f to 10f, 17f to 6f, 17f to 21f, 3f to 21f); line(20f, 3f, 20f, 21f) }
                RfSymbol.MODEL -> { drawRect(color, Offset(6f, 6f), Size(12f, 12f), style = Stroke(1.8f)); listOf(8f, 12f, 16f).forEach { line(it, 3f, it, 6f); line(it, 18f, it, 21f) } }
                RfSymbol.INFO -> { drawCircle(color, 9f, Offset(12f, 12f), style = Stroke(1.8f)); drawCircle(color, 1f, Offset(12f, 7f)); line(12f, 11f, 12f, 17f) }
                RfSymbol.SNAPSHOT -> { path(3f to 7f, 8f to 7f, 10f to 4f, 15f to 4f, 17f to 7f, 21f to 7f, 21f to 20f, 3f to 20f, 3f to 7f); drawCircle(color, 4f, Offset(12f, 13f), style = Stroke(1.8f)) }
                RfSymbol.CHECK -> path(4f to 12f, 9f to 17f, 20f to 6f)
            }
        }
    }
}
