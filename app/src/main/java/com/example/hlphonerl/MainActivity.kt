package com.example.hlphonerl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.hlphonerl.engine.RlEngine

class MainActivity : ComponentActivity() {
    private var engine: RlEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = RlEngine("xyz:SP500")
        setContent {
            MaterialTheme {
                val e = engine!!
                val state by e.state.collectAsState()
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("HL Phone RL", style = MaterialTheme.typography.headlineMedium)
                        Text("Pure on-device virtual RL from HyperLiquid L2 only")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { e.start() }, enabled = !state.running) { Text("Start") }
                            OutlinedButton(onClick = { e.stop() }, enabled = state.running) { Text("Stop") }
                        }
                        HorizontalDivider()
                        Metric("Status", state.status)
                        Metric("Coin", state.coin)
                        Metric("Policy", state.policy)
                        Metric("Learns", state.learningRule)
                        HorizontalDivider()
                        Metric("Mid", "%.4f".format(state.mid))
                        Metric("Spread", "%.3f bps".format(state.spreadBps))
                        Metric("Position", state.position)
                        Metric("Last action", state.action)
                        Metric("Reason", state.reason)
                        HorizontalDivider()
                        Metric("Live equity", "%.4f".format(state.equity))
                        Metric("Last reward", "%.6f".format(state.reward))
                        Metric("Realized PnL", "%.4f".format(state.realizedPnl))
                        Metric("Replay", state.replay.toString())
                        Metric("Updates", state.updates.toString())
                        Metric("Epsilon", "%.3f".format(state.epsilon))
                        Text("Q-values WAIT/LONG/SHORT/HOLD/EXIT: ${state.qValues}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "Virtual trading only. This MVP learns from book-walking simulated fills; no private key, no real orders.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        engine?.stop()
        super.onDestroy()
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
