package com.chipherlock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.progressindicator.LinearProgressIndicator

class NetworkFragment : Fragment() {

    private lateinit var btnScan: Button
    private lateinit var tvResults: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progress: LinearProgressIndicator

    private val handler = Handler(Looper.getMainLooper())
    private var scanReceiver: BroadcastReceiver? = null
    private val scanTimeout = Runnable { finishScan() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_network, container, false)

        btnScan   = view.findViewById(R.id.btn_scan_network)
        tvResults = view.findViewById(R.id.tv_network_results)
        tvStatus  = view.findViewById(R.id.tv_network_status)
        progress  = view.findViewById(R.id.progress_network)

        btnScan.setOnClickListener { startScan() }

        return view
    }

    override fun onDestroyView() {
        cancelScan()
        super.onDestroyView()
    }

    // ── Scan lifecycle ───────────────────────────────────────────────────────

    private fun startScan() {
        val ctx = requireContext()
        val wifiManager = ctx.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        setScanning(true)

        val hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasLocation) {
            finishScan()
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = finishScan()
        }
        scanReceiver = receiver
        androidx.core.content.ContextCompat.registerReceiver(
            ctx, receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        @Suppress("DEPRECATION")
        val started = wifiManager.startScan()
        // Android throttles scans; a refused scan still has usable cached results.
        handler.postDelayed(scanTimeout, if (started) SCAN_TIMEOUT_MS else 600L)
    }

    private fun finishScan() {
        if (!isAdded) return
        cancelScan()
        tvResults.text = buildReport()
        setScanning(false)
    }

    private fun cancelScan() {
        handler.removeCallbacks(scanTimeout)
        scanReceiver?.let { r ->
            try { requireContext().unregisterReceiver(r) } catch (_: Exception) { }
        }
        scanReceiver = null
    }

    private fun setScanning(scanning: Boolean) {
        btnScan.isEnabled = !scanning
        btnScan.text = getString(if (scanning) R.string.scanning_network else R.string.scan_network)
        progress.visibility = if (scanning) View.VISIBLE else View.GONE
        tvStatus.visibility = if (scanning) View.VISIBLE else View.GONE
        if (scanning) tvResults.text = ""
    }

    // ── Report ───────────────────────────────────────────────────────────────

    private fun buildReport(): CharSequence {
        val ctx = requireContext()
        val wifiManager = ctx.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        val scanResults: List<ScanResult> = try {
            wifiManager.scanResults.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }

        val sb = SpannableStringBuilder()

        if (scanResults.isEmpty()) {
            sb.appendStyled("No networks found\n\n", CipherPalette.warning, bold = true)
            sb.append("Turn on Wi-Fi and Location, then scan again.\n\n")
            sb.append("Android limits how often apps can scan. If nothing appears, wait 30 seconds and try again.")
            return sb
        }

        val connectedSsid = try {
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo?.ssid?.replace("\"", "")
                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        } catch (e: Exception) { null }

        if (connectedSsid != null) {
            sb.appendStyled("CONNECTED TO\n", CipherPalette.textMuted, bold = true)
            sb.appendStyled("$connectedSsid\n\n", CipherPalette.textPrimary, bold = true)
        }

        val ssidCounts = scanResults
            .groupBy { it.SSID?.trim() ?: "" }
            .filter { it.key.isNotEmpty() }
            .mapValues { it.value.size }

        val suspiciousKeywords = listOf(
            "free", "public", "guest", "hack", "open", "test", "wifi", "hotspot", "internet"
        )

        data class NetworkResult(
            val priority: Int,
            val name: String,
            val notes: List<String>,
            val rssi: Int
        )
        val results = mutableListOf<NetworkResult>()

        for (result in scanResults) {
            val ssid    = result.SSID?.trim()?.replace("\"", "") ?: ""
            val rssi    = result.level
            val hidden  = ssid.isEmpty() || ssid == "<unknown ssid>"
            val threats  = mutableListOf<String>()
            val cautions = mutableListOf<String>()

            if (!hidden && (ssidCounts[ssid] ?: 0) > 1) {
                threats.add("Evil twin — ${ssidCounts[ssid]} access points share this name")
            }
            if (!hidden && suspiciousKeywords.any { ssid.lowercase().contains(it) }) {
                cautions.add("Suspicious name — public networks can expose data")
            }
            if (hidden) {
                cautions.add("Hidden SSID — verify before connecting")
            }
            if (rssi < -85) {
                cautions.add("Very weak signal — possible distant or spoofed access point")
            }

            val displayName = if (hidden) "(hidden network)" else ssid
            results += when {
                threats.isNotEmpty()  -> NetworkResult(0, displayName, threats, rssi)
                cautions.isNotEmpty() -> NetworkResult(1, displayName, cautions, rssi)
                else                  -> NetworkResult(2, displayName, emptyList(), rssi)
            }
        }

        results.sortBy { it.priority }

        val threatCount  = results.count { it.priority == 0 }
        val cautionCount = results.count { it.priority == 1 }
        val safeCount    = results.size - threatCount - cautionCount

        sb.appendStyled("${results.size} NEARBY NETWORKS\n", CipherPalette.textMuted, bold = true)
        sb.appendStyled("$threatCount threat  ", CipherPalette.danger, bold = true)
        sb.appendStyled("$cautionCount caution  ", CipherPalette.warning, bold = true)
        sb.appendStyled("$safeCount safe\n\n", CipherPalette.success, bold = true)

        for (r in results) {
            val (label, color) = when (r.priority) {
                0    -> "THREAT" to CipherPalette.danger
                1    -> "CAUTION" to CipherPalette.warning
                else -> "SAFE" to CipherPalette.success
            }
            sb.appendStyled("$label  ", color, bold = true)
            sb.appendStyled("${r.name}\n", CipherPalette.textPrimary, bold = true)
            r.notes.forEach { sb.append("   • $it\n") }
            sb.appendStyled("   Signal ${r.rssi} dBm  ${signalBars(r.rssi)}\n\n", CipherPalette.textMuted)
        }

        sb.appendStyled("SAFETY TIPS\n", CipherPalette.primary, bold = true)
        sb.append("• Avoid banking on public Wi-Fi\n")
        sb.append("• Use a VPN on unfamiliar networks\n")
        sb.append("• Never connect to evil twin networks\n")
        sb.append("• Verify network names with staff before connecting")

        return sb
    }

    private fun signalBars(rssi: Int) = when {
        rssi >= -55 -> "▓▓▓▓"
        rssi >= -65 -> "▓▓▓░"
        rssi >= -75 -> "▓▓░░"
        else        -> "▓░░░"
    }

    private fun SpannableStringBuilder.appendStyled(
        text: String, color: Int, bold: Boolean = false
    ) {
        val start = length
        append(text)
        setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) {
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 8_000L
    }
}
