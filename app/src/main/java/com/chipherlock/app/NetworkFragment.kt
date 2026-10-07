package com.chipherlock.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

class NetworkFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_network, container, false)

        val btnScan = view.findViewById<Button>(R.id.btn_scan_network)
        val tvResults = view.findViewById<TextView>(R.id.tv_network_results)

        btnScan.setOnClickListener {
            tvResults.text = scanNetwork()
        }

        return view
    }

    private fun scanNetwork(): String {
        val wifiManager = requireContext()
            .applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        @Suppress("DEPRECATION")
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            wifiManager.startScan()
        }

        val scanResults = wifiManager.scanResults
        if (scanResults.isNullOrEmpty()) {
            return "⚠️ No networks found.\n\nEnable WiFi and Location to scan nearby networks.\n\n" +
                    "Note: Android limits scan frequency — wait 30 seconds and try again."
        }

        val sb = StringBuilder()

        val connectedSsid = try {
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo?.ssid?.replace("\"", "")
                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        } catch (e: Exception) { null }

        if (connectedSsid != null) {
            sb.append("📶 CONNECTED TO: $connectedSsid\n")
            sb.append("─────────────────────────────────\n\n")
        }

        sb.append("🔍 NEARBY NETWORKS (${scanResults.size} found)\n")
        sb.append("─────────────────────────────────\n\n")

        val ssidCounts = scanResults
            .groupBy { it.SSID?.trim() ?: "" }
            .filter { it.key.isNotEmpty() }
            .mapValues { it.value.size }

        val suspiciousKeywords = listOf(
            "free", "public", "guest", "hack", "open", "test", "wifi", "hotspot", "internet"
        )

        data class NetworkResult(val emoji: String, val priority: Int, val text: String)
        val results = mutableListOf<NetworkResult>()

        for (result in scanResults) {
            val ssid    = result.SSID?.trim()?.replace("\"", "") ?: ""
            val rssi    = result.level
            val hidden  = ssid.isEmpty() || ssid == "<unknown ssid>"
            val threats  = mutableListOf<String>()
            val cautions = mutableListOf<String>()

            if (!hidden && (ssidCounts[ssid] ?: 0) > 1) {
                threats.add("Evil twin — ${ssidCounts[ssid]} APs share this name")
            }
            if (!hidden && suspiciousKeywords.any { ssid.lowercase().contains(it) }) {
                cautions.add("Suspicious name — public networks can expose data")
            }
            if (hidden) {
                cautions.add("Hidden SSID — verify before connecting")
            }
            if (rssi < -85) {
                cautions.add("Very weak signal ($rssi dBm) — possible distant/spoofed AP")
            }

            val displayName = if (hidden) "(hidden network)" else ssid
            val signalBar = when {
                rssi >= -55 -> "▓▓▓▓"
                rssi >= -65 -> "▓▓▓░"
                rssi >= -75 -> "▓▓░░"
                else        -> "▓░░░"
            }

            when {
                threats.isNotEmpty() -> {
                    val text = "🔴 THREAT: $displayName\n" +
                            threats.joinToString("\n") { "   ⚠ $it" } + "\n" +
                            "   Signal: $rssi dBm $signalBar\n"
                    results.add(NetworkResult("🔴", 0, text))
                }
                cautions.isNotEmpty() -> {
                    val text = "🟡 CAUTION: $displayName\n" +
                            cautions.joinToString("\n") { "   ℹ $it" } + "\n" +
                            "   Signal: $rssi dBm $signalBar\n"
                    results.add(NetworkResult("🟡", 1, text))
                }
                else -> {
                    val text = "🟢 SAFE: $displayName\n   Signal: $rssi dBm $signalBar\n"
                    results.add(NetworkResult("🟢", 2, text))
                }
            }
        }

        results.sortBy { it.priority }
        results.forEach { sb.append(it.text).append("\n") }

        val threatCount  = results.count { it.emoji == "🔴" }
        val cautionCount = results.count { it.emoji == "🟡" }
        sb.append("─────────────────────────────────\n")
        sb.append("Summary: 🔴 $threatCount threat(s)  🟡 $cautionCount caution(s)  🟢 ${results.size - threatCount - cautionCount} safe\n\n")
        sb.append("💡 SAFETY TIPS\n─────────────────────────────────\n")
        sb.append("• Avoid banking on public WiFi\n")
        sb.append("• Use a VPN on unfamiliar networks\n")
        sb.append("• Never connect to evil twin networks\n")
        sb.append("• Verify network names with staff before connecting\n")

        return sb.toString()
    }

}