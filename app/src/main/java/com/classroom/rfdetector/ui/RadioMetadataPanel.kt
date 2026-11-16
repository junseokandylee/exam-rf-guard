package com.classroom.rfdetector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classroom.rfdetector.core.RadioMetadata
import com.classroom.rfdetector.core.detailFields

@Composable
internal fun RadioMetadataPanel(metadata: RadioMetadata?) {
    if (metadata == null) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ExplanationToggle("무선 정보 자세히", expanded, { expanded = !expanded })
        if (expanded) {
            metadata.detailFields().forEach { (label, value) ->
                Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                Text(value, color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
            }
            Text("기기·Android가 제공한 발견 정보입니다. 보안 광고나 서비스 UUID만으로 실제 기능·모델·위험성을 인증하지 않습니다.",
                color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
        }
    }
}
