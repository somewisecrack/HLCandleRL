package com.example.hlphonerl

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hlphonerl.engine.AppRuntime
import com.example.hlphonerl.engine.RlForegroundService

private val Ink = Color(0xFFE9EEF7)
private val Muted = Color(0xFF8F9BAE)
private val Panel = Color(0xFF121826)
private val Panel2 = Color(0xFF182132)
private val Line = Color(0xFF273246)
private val Green = Color(0xFF28E09A)
private val Red = Color(0xFFFF5B6E)
private val Amber = Color(0xFFFFC857)
private val Blue = Color(0xFF78A6FF)

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF070A0F),
                    surface = Panel,
                    primary = Green,
                    secondary = Blue,
                    onBackground = Ink,
                    onSurface = Ink
                )
            ) {
                val e = AppRuntime.engine
                val state by e.state.collectAsState()
                Dashboard(
                    state = state,
                    onStart = { startLearnerService() },
                    onStop = { stopLearnerService() }
                )
            }
        }
    }

    private fun startLearnerService() {
        val intent = Intent(this, RlForegroundService::class.java).setAction(RlForegroundService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopLearnerService() {
        startService(Intent(this, RlForegroundService::class.java).setAction(RlForegroundService.ACTION_STOP))
    }
}

@Composable
private fun Dashboard(
    state: com.example.hlphonerl.engine.EngineUiState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val pnlColor = when {
        state.equity > 0 -> Green
        state.equity < 0 -> Red
        else -> Ink
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF070A0F), Color(0xFF0A1020), Color(0xFF070A0F))
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Header(state.running, state.status, onStart, onStop)

            CardPanel {
                Text("Virtual PnL", color = Muted, style = MaterialTheme.typography.labelLarge)
                Text(
                    "%+.4f USDC".format(state.equity),
                    color = pnlColor,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiniStat("Realized", "%+.4f".format(state.realizedPnl), Modifier.weight(1f))
                    MiniStat("Last reward", "%+.5f".format(state.reward), Modifier.weight(1f))
                }
            }

            CardPanel {
                SectionTitle("Market")
                MetricGrid(
                    listOf(
                        "Coin" to state.coin,
                        "Mid" to "%.4f".format(state.mid),
                        "Spread" to "%.3f bps".format(state.spreadBps),
                        "Position" to state.position,
                        "HL taker" to "%.3f bps".format(state.crossFeeBps),
                        "Funding/hr" to "%.4f bps".format(state.fundingBpsPerHour)
                    )
                )
            }

            CardPanel {
                SectionTitle("Policy being learned")
                BodyText(state.policy)
                Spacer(Modifier.height(6.dp))
                BodyText(state.learningRule)
                BodyText("Costs: ${state.costSource}")
                Spacer(Modifier.height(12.dp))
                MetricGrid(
                    listOf(
                        "Action" to state.action,
                        "Reason" to state.reason,
                        "Replay" to state.replay.toString(),
                        "Updates" to state.updates.toString(),
                        "Epsilon" to "%.3f".format(state.epsilon)
                    )
                )
            }

            CardPanel {
                SectionTitle("Q-values")
                Text("WAIT / LONG / SHORT / HOLD / EXIT", color = Muted, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    state.qValues.ifBlank { "Waiting for book..." },
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }

            Text(
                "Virtual only. No private key. No real orders. Learns from HyperLiquid L2 book-walking simulated fills.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    }
}

@Composable
private fun Header(running: Boolean, status: String, onStart: () -> Unit, onStop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("HL Phone RL", color = Ink, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("L2-only virtual reinforcement learner", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            StatusPill(running)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onStart,
                enabled = !running,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Start learner") }
            OutlinedButton(
                onClick = onStop,
                enabled = running,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Stop") }
        }
        Text(status, color = if (status.contains("failure", true)) Red else Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusPill(running: Boolean) {
    val color = if (running) Green else Amber
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(999.dp), border = ButtonDefaults.outlinedButtonBorder) {
        Text(if (running) "LIVE" else "PAUSED", color = color, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CardPanel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.96f)),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), color = Blue, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun BodyText(text: String) {
    Text(text, color = Ink, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Panel2, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        Text(value, color = Ink, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MetricGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) -> MetricTile(label, value, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Panel2, RoundedCornerShape(14.dp))
            .padding(10.dp)
            .heightIn(min = 58.dp)
    ) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Text(value.ifBlank { "—" }, color = Ink, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
    }
}
