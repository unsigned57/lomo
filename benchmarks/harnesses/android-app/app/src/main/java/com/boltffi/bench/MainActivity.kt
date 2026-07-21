package com.boltffi.bench

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                BenchmarkScreen(onShare = { report -> shareReport(report) })
            }
        }
    }

    private fun shareReport(report: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(intent, "Share Benchmark Results"))
    }
}

data class BenchmarkResult(
    val name: String,
    val category: String,
    val boltffiTimeNs: Long,
    val uniffiTimeNs: Long,
) {
    val speedup: Double get() = uniffiTimeNs.toDouble() / boltffiTimeNs.toDouble().coerceAtLeast(1.0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(onShare: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<BenchmarkResult>>(emptyList()) }
    var isRunning by remember { mutableStateOf(false) }
    var currentTest by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var phaseReport by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("BoltFFI vs UniFFI") })
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                isRunning = true
                                results = emptyList()
                                progress = 0f
                                val newResults = withContext(Dispatchers.Default) {
                                    runAllBenchmarks { name, pct ->
                                        currentTest = name
                                        progress = pct
                                    }
                                }
                                results = newResults
                                isRunning = false
                                currentTest = ""
                                progress = 1f
                            }
                        },
                        enabled = !isRunning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (isRunning) "Running..." else "Run All")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isRunning = true
                                currentTest = "Phase isolation..."
                                val report = withContext(Dispatchers.Default) {
                                    runPhaseIsolation()
                                }
                                android.util.Log.i("BoltFFIBench", report)
                                phaseReport = report
                                isRunning = false
                                currentTest = ""
                            }
                        },
                        enabled = !isRunning,
                    ) {
                        Text("Isolate")
                    }
                    if (results.isNotEmpty()) {
                        OutlinedButton(onClick = { onShare(generateReport(results)) }) {
                            Text("Share")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (isRunning) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    text = currentTest,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (phaseReport.isNotEmpty()) {
                Text(
                    text = phaseReport,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(16.dp),
                )
            }

            if (results.isEmpty() && !isRunning) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Tap 'Run All' to start",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val grouped = results.groupBy { it.category }
                LazyColumn {
                    grouped.keys.sorted().forEach { category ->
                        item {
                            Text(
                                text = category,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        items(grouped[category] ?: emptyList()) { result ->
                            ResultRow(result)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ResultRow(result: BenchmarkResult) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = result.name,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "boltffi: ${formatTime(result.boltffiTimeNs)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF4CAF50),
            )
            Text(
                text = "uniffi: ${formatTime(result.uniffiTimeNs)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFF9800),
            )
            Text(
                text = "%.1fx".format(result.speedup),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                fontSize = 13.sp,
                color = if (result.speedup >= 1.0) Color(0xFF4CAF50) else Color(0xFFF44336),
            )
        }
    }
    HorizontalDivider()
}

fun formatTime(ns: Long): String = when {
    ns >= 1_000_000 -> "%.2f ms".format(ns / 1_000_000.0)
    ns >= 1_000 -> "%.2f us".format(ns / 1_000.0)
    else -> "$ns ns"
}

fun generateReport(results: List<BenchmarkResult>): String {
    val sb = StringBuilder()
    sb.appendLine("# BoltFFI vs UniFFI Benchmark Results")
    sb.appendLine()
    sb.appendLine("**Device:** ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})")
    sb.appendLine("**Date:** ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}")
    sb.appendLine()
    sb.appendLine("| Benchmark | BoltFFI | UniFFI | Speedup |")
    sb.appendLine("|-----------|------|--------|---------|")
    results.forEach { r ->
        sb.appendLine("| ${r.name} | ${formatTime(r.boltffiTimeNs)} | ${formatTime(r.uniffiTimeNs)} | ${"%.1fx".format(r.speedup)} |")
    }
    sb.appendLine()
    sb.appendLine("---")
    sb.appendLine("Generated by BoltFFI Android Benchmark")
    return sb.toString()
}
