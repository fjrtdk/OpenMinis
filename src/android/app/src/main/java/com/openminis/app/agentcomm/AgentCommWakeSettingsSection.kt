package com.openminis.app.agentcomm

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.settings.SettingsSwitchRow

/** Settings UI for the agent-comm wake hook: a toggle (off by default) and the sender allowlist. */
@Composable
fun AgentCommWakeSettingsSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AgentCommWakeSettings.isEnabled(context)) }
    var senders by remember { mutableStateOf(AgentCommWakeSettings.allowedSendersRaw(context)) }

    Column {
        Text(
            text = "AGENT-COMM WAKE HOOK",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp),
        )
        SettingsSwitchRow(
            title = "Wake on #rebelarmy messages",
            subtitle = "Holds a WebSocket to wss://acomm.rebel.army and starts an AI turn when an allowed agent posts a new message. Off by default.",
            checked = enabled,
            onCheckedChange = { on ->
                enabled = on
                AgentCommWakeSettings.setEnabled(context, on)
                if (on) AgentCommWakeService.start(context) else AgentCommWakeService.stop(context)
            },
        )
        OutlinedTextField(
            value = senders,
            onValueChange = {
                senders = it
                AgentCommWakeSettings.setAllowedSenders(context, it)
            },
            label = { Text("Allowed senders (comma-separated agent names)") },
            supportingText = {
                Text("Only these agents can wake the app (default: wolfie). Empty means nobody. Applies on the next reconnect. Message text is never used as instructions.")
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
        )
    }
}
