package com.classroom.rfdetector.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classroom.rfdetector.core.BleJournalStatus

@Composable
internal fun BleJournalPanel(status: BleJournalStatus, simulation: Boolean, exporting: Boolean,
    exportMessage: String?, onExport: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(color = RfColors.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("ble-journal")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RfGlyph(RfSymbol.CLOCK)
                Text("BLE 광고 기록", fontWeight = FontWeight.Bold)
            }
            when {
                simulation -> Text("연습 중 · 실제 BLE 광고 기록과 분리합니다", color = RfColors.warning, style = MaterialTheme.typography.bodySmall)
                status.sessionId == null -> Text("실제 탐색을 시작하면 광고별로 기록합니다", style = MaterialTheme.typography.bodySmall)
                else -> {
                    Text("앱에 전달 ${status.received}회 · 저장 완료 ${status.written}회", color = RfColors.primary)
                    Text("저장 대기 ${status.pending}회 · 저장 실패 ${status.failed}회", color = if (status.failed > 0) RfColors.warning else RfColors.textDim,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            status.error?.let { Text("기록 누락 가능 · $it", color = RfColors.warning, style = MaterialTheme.typography.bodySmall) }
            Text("이름 없는 광고·반복 광고도 기록합니다. 무선 신호 전체의 수신률을 뜻하지 않습니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onExport, enabled = !exporting, modifier = Modifier.fillMaxWidth()) {
                RfGlyph(RfSymbol.SNAPSHOT)
                Spacer(Modifier.width(6.dp))
                Text(if (exporting) "광고 기록 내보내는 중" else "BLE 광고 기록 파일로 저장")
            }
            exportMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            ExplanationToggle("어떤 광고를 기록하나요?", expanded) { expanded = !expanded }
            if (expanded) {
                Text("Android가 현재 탐색에 전달한 광고 결과를 별도 기록합니다. 같은 주소의 반복 결과도 행을 합치지 않습니다. 광고 원본 바이트가 없거나 시각을 검증하지 못한 결과도 사유를 남기며, 확인되지 않은 시각을 장비 비교에 섞지 않습니다.", style = MaterialTheme.typography.bodySmall)
                Text("비콘 형식을 모르거나 이름·제조사·서비스 정보가 없어도 기록 대상입니다. 기본·확장 광고 범위는 휴대전화가 지원하는 기능에 따라 달라집니다.", style = MaterialTheme.typography.bodySmall)
                Text("파일에는 모든 실제 탐색 세션의 광고 원본·주소·시각·신호 세기와 탐색 상태 변경이 들어갑니다. JSONL 형식이며 전파 전체나 연결된 두 기기의 통신 내용을 녹음한 파일은 아닙니다. 공유 전 식별 정보를 확인하세요.", style = MaterialTheme.typography.bodySmall)
                Text("앱을 벗어나면 수신이 멈춥니다. 저장 대기 중 강제 종료·전원 꺼짐, 저장 공간 부족, 처리량 초과 시 미저장 기록이 생길 수 있습니다. 정상 종료 표시가 없는 세션은 끝부분의 완전성을 알 수 없습니다.", style = MaterialTheme.typography.bodySmall, color = RfColors.textDim)
            }
        }
    }
}
