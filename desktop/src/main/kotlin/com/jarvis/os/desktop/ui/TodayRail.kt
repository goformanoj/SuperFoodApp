package com.jarvis.os.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jarvis.os.desktop.DesktopAssistant
import com.jarvis.os.desktop.Telemetry

/**
 * The instrument rail beside a conversation: the same HUD panels as the cockpit,
 * real data only. Tasks and routines are not shown until their phases land.
 */
@Composable
fun TodayRail(a: DesktopAssistant, telemetry: Telemetry, onMemory: () -> Unit) {
    Column(
        Modifier.width(290.dp).fillMaxHeight().background(J.Glass).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ChronoPanel(telemetry, Modifier.fillMaxWidth())
        LinkPanel(a, Modifier.fillMaxWidth())
        SystemPanel(telemetry, Modifier.fillMaxWidth())
        MemoryPanel(a, onMemory, Modifier.fillMaxWidth())
    }
}
