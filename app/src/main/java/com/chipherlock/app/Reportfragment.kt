package com.chipherlock.app

import android.content.ContentValues
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment

import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ReportFragment : Fragment() {

    // ── Data model ───────────────────────────────────────────────────────

    data class ReportData(
        val overallScore: Int,
        val scoreLabel: String,
        val scoreColor: Int,
        val passwordStrengthPct: Int,
        val totalPasswords: Int,
        val strongPasswords: Int,
        val reusedPairs: List<Pair<String, String>>,
        val weakServices: List<String>,
        val threatsThisWeek: Int,
        val lessonsCompleted: Int,
        val totalLessons: Int = 18
    )

    private fun m(dp: Int, density: Float) = (dp * density).toInt()

    // ════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ════════════════════════════════════════════════════════════════════

    // Keep references so onResume can rebuild without re-inflating
    private var rootLayout: LinearLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val ctx    = requireContext()
        val dp     = ctx.resources.displayMetrics.density
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(Color.parseColor("#F0F4F8"))
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = m(16, dp)
            setPadding(p, p, p, p)
        }
        scroll.addView(root)
        rootLayout = root
        buildReport(ctx, dp, root)
        return scroll
    }

    private val vaultChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val root = rootLayout ?: return
            val ctx  = context ?: return
            val dp   = ctx.resources.displayMetrics.density
            root.removeAllViews()
            buildReport(ctx, dp, root)
        }
    }

    // Re-gather and rebuild every time the Report tab becomes visible
    override fun onResume() {
        super.onResume()
        val root = rootLayout ?: return
        val ctx  = requireContext()
        val dp   = ctx.resources.displayMetrics.density
        root.removeAllViews()
        buildReport(ctx, dp, root)
        androidx.localbroadcastmanager.content.LocalBroadcastManager
            .getInstance(ctx)
            .registerReceiver(
                vaultChangedReceiver,
                android.content.IntentFilter("com.chipherlock.VAULT_DATA_CHANGED")
            )
    }

    override fun onPause() {
        super.onPause()
        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(requireContext())
                .unregisterReceiver(vaultChangedReceiver)
        } catch (_: Exception) { }
    }

    private fun buildReport(ctx: Context, dp: Float, root: LinearLayout) {
        val data = gatherData(ctx)
        root.addView(buildHeader(ctx, dp))
        root.addView(buildScoreSection(ctx, dp, data))
        root.addView(buildStatRow(ctx, dp, data))
        root.addView(buildRecommendations(ctx, dp, data))
        root.addView(buildExportButton(ctx, dp, data))
        root.addView(buildFooter(ctx, dp))
    }

    // ════════════════════════════════════════════════════════════════════
    // DATA GATHERING
    // ════════════════════════════════════════════════════════════════════

    private fun gatherData(ctx: Context): ReportData {
        val pwStats         = getPasswordStats(ctx)
        val strengthPct     = if (pwStats.total == 0) 0 else (pwStats.strong * 100 / pwStats.total)
        val weekAgo         = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val threatsThisWeek = SmsFragment.loadHistory(ctx)
            .count { it.timestamp >= weekAgo && it.riskLevel != "LOW" }
        val lessonsCompleted = getLessonsCompleted(ctx)

        // No passwords saved = 0 score contribution (not a free 60), penalises overall
        val pwScore     = if (pwStats.total == 0) 0 else strengthPct
        val lessonScore = (lessonsCompleted * 100 / 18).coerceAtMost(100)
        val threatScore = when {
            threatsThisWeek == 0 -> 100
            threatsThisWeek <= 2 ->  90
            threatsThisWeek <= 5 ->  80
            else                 ->  70
        }
        val overall = (pwScore * 0.40 + lessonScore * 0.35 + threatScore * 0.25).toInt()

        val (label, color) = when {
            overall >= 85 -> "EXCELLENT! Keep it up."  to Color.parseColor("#2E7D32")
            overall >= 70 -> "GOOD! Keep it up."       to Color.parseColor("#4CAF50")
            overall >= 50 -> "FAIR. Room to improve."  to Color.parseColor("#FF9800")
            else          -> "NEEDS ATTENTION."        to Color.parseColor("#F44336")
        }

        return ReportData(
            overallScore        = overall,
            scoreLabel          = label,
            scoreColor          = color,
            passwordStrengthPct = strengthPct,
            totalPasswords      = pwStats.total,
            strongPasswords     = pwStats.strong,
            reusedPairs         = pwStats.reusedPairs,
            weakServices        = pwStats.weakServices,
            threatsThisWeek     = threatsThisWeek,
            lessonsCompleted    = lessonsCompleted
        )
    }

    private data class PwStats(
        val total: Int,
        val strong: Int,
        val reusedPairs: List<Pair<String, String>>,
        val weakServices: List<String>
    )

    private fun getPasswordStats(ctx: Context): PwStats {
        return try {
            // Use VaultFragment's static loader to read the vault_entries JSON array
            // correctly — previously this used prefs.all which reads raw ESP metadata
            // keys instead of the actual password entries.
            val vaultEntries = VaultFragment.loadEntriesStatic(ctx)

            val weakList    = vaultEntries.filter { !isStrongPassword(it.password) }
                .map { it.service }
            val strongCount = vaultEntries.count { isStrongPassword(it.password) }
            // CharArray uses reference equality in HashMap; group by content via String key.
            val reusedPairs = vaultEntries
                .groupBy { String(it.password) }
                .values
                .filter { it.size >= 2 }
                .map { grp -> Pair(grp[0].service, grp[1].service) }

            val result = PwStats(vaultEntries.size, strongCount, reusedPairs, weakList)
            // REM-001 — stats are computed; wipe the in-method copy of CharArrays.
            vaultEntries.forEach { it.wipe() }
            result
        } catch (e: Exception) {
            PwStats(0, 0, emptyList(), emptyList())
        }
    }

    // Strong = 12+ chars AND has upper, lower, digit, AND special char.
    // REM-001 — operates directly on CharArray to avoid String materialization.
    private fun isStrongPassword(pw: CharArray) =
        pw.size >= 12 &&
                pw.any { it.isUpperCase() } &&
                pw.any { it.isLowerCase() } &&
                pw.any { it.isDigit() } &&
                pw.any { !it.isLetterOrDigit() }


    // Reads the lesson count written by HelpDeskFragment.
    // Report mirrors this counter for the Security Score.
    private fun getLessonsCompleted(ctx: Context): Int =
        ctx.getSharedPreferences("helpdesk_prefs", Context.MODE_PRIVATE)
            .getInt("helpdesk_lessons_read", 0)

    // ════════════════════════════════════════════════════════════════════
    // UI — HEADER CARD
    // ════════════════════════════════════════════════════════════════════

    private fun buildHeader(ctx: Context, dp: Float): View {
        val sdf = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background  = cardBg(dp)
            val p = m(16, dp)
            setPadding(p, p, p, p)
            layoutParams = cardLp(dp)

            addView(TextView(ctx).apply {
                text     = "CipherLock Security Report"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#1A237E"))
            })
            addView(TextView(ctx).apply {
                text     = "Confidential  |  ${sdf.format(Date())}  |  Personal use only"
                textSize = 11f
                setTextColor(Color.GRAY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = m(4, dp) }
            })
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // UI — SCORE RING
    // ════════════════════════════════════════════════════════════════════

    private fun buildScoreSection(ctx: Context, dp: Float, data: ReportData): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            background  = cardBg(dp)
            val p = m(20, dp)
            setPadding(p, p, p, p)
            layoutParams = cardLp(dp)

            addView(ScoreRingView(ctx, data.overallScore, data.scoreColor, dp).apply {
                layoutParams = LinearLayout.LayoutParams(m(160, dp), m(160, dp)).also {
                    it.gravity = Gravity.CENTER_HORIZONTAL
                }
            })
            addView(TextView(ctx).apply {
                text     = data.scoreLabel
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(data.scoreColor)
                gravity  = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = m(10, dp) }
            })
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // UI — STAT ROW  (Passwords | Threats | Lessons)
    // ════════════════════════════════════════════════════════════════════

    private fun buildStatRow(ctx: Context, dp: Float, data: ReportData): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            // Fixed height forces all 3 boxes to be exactly the same size
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                m(88, dp)
            ).also { it.bottomMargin = m(12, dp) }

            // Passwords box: red warning if none saved, green if all strong, orange if mixed
            val pwValue = if (data.totalPasswords == 0) "—" else "${data.passwordStrengthPct}%"
            val pwSub   = if (data.totalPasswords == 0) "none saved" else "strong"
            val (pwBg, pwFg) = when {
                data.totalPasswords == 0                        ->
                    Color.parseColor("#FFEBEE") to Color.parseColor("#C62828") // red — warning
                data.passwordStrengthPct >= 80                  ->
                    Color.parseColor("#E8F5E9") to Color.parseColor("#2E7D32") // green — good
                data.passwordStrengthPct >= 50                  ->
                    Color.parseColor("#FFF8E1") to Color.parseColor("#F57F17") // amber — fair
                else                                            ->
                    Color.parseColor("#FFEBEE") to Color.parseColor("#C62828") // red — weak
            }
            addView(statBox(ctx, dp, "Passwords", pwValue, pwSub, pwBg, pwFg))
            addView(gapH(ctx, m(8, dp)))

            // Threats box: green when 0 (safe), orange/red when threats exist
            val (thrBg, thrFg) = when {
                data.threatsThisWeek == 0  ->
                    Color.parseColor("#E8F5E9") to Color.parseColor("#2E7D32") // green — safe
                data.threatsThisWeek <= 2  ->
                    Color.parseColor("#FFF3E0") to Color.parseColor("#E65100") // orange — caution
                else                       ->
                    Color.parseColor("#FFEBEE") to Color.parseColor("#C62828") // red — danger
            }
            addView(statBox(ctx, dp, "Threats",
                "${data.threatsThisWeek}", "this week", thrBg, thrFg))
            addView(gapH(ctx, m(8, dp)))

            // Lessons box: blue when complete, orange when partial, red when none started
            val (lsnBg, lsnFg) = when {
                data.lessonsCompleted >= data.totalLessons      ->
                    Color.parseColor("#E3F2FD") to Color.parseColor("#1565C0") // blue — complete
                data.lessonsCompleted >= data.totalLessons / 2  ->
                    Color.parseColor("#FFF8E1") to Color.parseColor("#F57F17") // amber — halfway
                data.lessonsCompleted > 0                       ->
                    Color.parseColor("#FFF3E0") to Color.parseColor("#E65100") // orange — started
                else                                            ->
                    Color.parseColor("#FFEBEE") to Color.parseColor("#C62828") // red — none
            }
            addView(statBox(ctx, dp, "Lessons",
                "${data.lessonsCompleted}/${data.totalLessons}", "completed", lsnBg, lsnFg))
        }
    }

    // 3-line layout: [label] + [big value] + [sub-label]
    // Each on its own TextView with isSingleLine=true — wrapping impossible
    private fun statBox(ctx: Context, dp: Float,
                        label: String, value: String, subLabel: String,
                        bg: Int, fg: Int) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            background  = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = m(10, dp).toFloat()
                setColor(bg)
                setStroke(m(1, dp), fg)
            }
            setPadding(m(6, dp), 0, m(6, dp), 0)
            // MATCH_PARENT height = always matches fixed-height parent row
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)

            addView(TextView(ctx).apply {
                text = label; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(fg); gravity = Gravity.CENTER; isSingleLine = true
            })
            addView(TextView(ctx).apply {
                text = value; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(fg); gravity = Gravity.CENTER; isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = m(2, dp) }
            })
            addView(TextView(ctx).apply {
                text = subLabel; textSize = 10f
                setTextColor(fg); gravity = Gravity.CENTER; isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = m(1, dp) }
            })
        }

    // ════════════════════════════════════════════════════════════════════
    // UI — RECOMMENDATIONS  (2-column grid)
    // ════════════════════════════════════════════════════════════════════

    private fun buildRecommendations(ctx: Context, dp: Float, data: ReportData): View {
        val section = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background  = cardBg(dp)
            val p = m(16, dp); setPadding(p, p, p, p)
            layoutParams = cardLp(dp)
        }

        section.addView(TextView(ctx).apply {
            text     = "Detailed Recommendations"
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1A237E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = m(12, dp) }
        })

        // Build recommendation list
        data class Rec(val title: String, val body: String, val bg: Int)
        val recs = mutableListOf<Rec>()

        // ── Password reuse ──
        if (data.reusedPairs.isNotEmpty()) {
            val (a, b) = data.reusedPairs[0]
            recs += Rec(
                "⚠️  Password Reuse Detected",
                "$a and $b share the same password. Use unique passwords for each service.",
                Color.parseColor("#FFF8E1")
            )
        }

        // ── Weak passwords ──
        if (data.weakServices.isNotEmpty()) {
            val names = data.weakServices.take(2).joinToString(", ")
            val plural = if (data.weakServices.size > 1) "s are" else " is"
            recs += Rec(
                "🔑  Weak Password${if (data.weakServices.size > 1) "s" else ""}",
                "$names password$plural weak. Use 12+ chars with uppercase, numbers & symbols.",
                Color.parseColor("#FCE4EC")
            )
        }

        // ── All passwords strong (positive) ──
        if (data.totalPasswords > 0 && data.weakServices.isEmpty() && data.reusedPairs.isEmpty()) {
            val s = if (data.totalPasswords > 1) "s are" else " is"
            recs += Rec(
                "✅  Passwords Look Good",
                "All ${data.totalPasswords} saved password$s strong and unique. Great job!",
                Color.parseColor("#E8F5E9")
            )
        }

        // ── Vault is empty ──
        if (data.totalPasswords == 0) {
            recs += Rec(
                "🔐  Vault is Empty",
                "Save passwords in the Vault tab to get strength analysis here.",
                Color.parseColor("#E3F2FD")
            )
        }

        // ── Threats ──
        if (data.threatsThisWeek > 0) {
            val s = if (data.threatsThisWeek > 1) "s" else ""
            recs += Rec(
                "🛡️  ${data.threatsThisWeek} Threat$s This Week",
                "CHIPHERLOCK blocked suspicious SMS. Never tap links from unknown senders.",
                Color.parseColor("#FFF3E0")
            )
        } else {
            recs += Rec(
                "✅  No Threats This Week",
                "No suspicious messages in the past 7 days. Stay vigilant!",
                Color.parseColor("#E8F5E9")
            )
        }

        // ── Lessons ──
        val remaining = data.totalLessons - data.lessonsCompleted
        recs += if (remaining > 0)
            Rec(
                "📚  $remaining Lesson${if (remaining > 1) "s" else ""} Remaining",
                "Read all ${data.totalLessons} Help Desk articles to earn your Cyber Defender badge.",
                Color.parseColor("#EDE7F6")
            )
        else
            Rec(
                "🏆  Cyber Defender Badge!",
                "You've read all ${data.totalLessons} Help Desk articles. Outstanding!",
                Color.parseColor("#E8F5E9")
            )

        // ── Pyramid layout: first card full-width, rest in 2-column pairs ──
        if (recs.isEmpty()) return section

        // Top card — full width
        section.addView(recCard(ctx, dp, recs[0].title, recs[0].body, recs[0].bg).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = m(8, dp) }
        })

        // Remaining cards in 2-column pairs
        var i = 1
        while (i < recs.size) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = m(8, dp) }
            }
            row.addView(recCard(ctx, dp, recs[i].title, recs[i].body, recs[i].bg))
            if (i + 1 < recs.size) {
                row.addView(gapH(ctx, m(8, dp)))
                row.addView(recCard(ctx, dp, recs[i + 1].title, recs[i + 1].body, recs[i + 1].bg))
            } else {
                // Odd card: fill remaining half with an invisible spacer
                row.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                })
            }
            section.addView(row)
            i += 2
        }

        return section
    }

    private fun recCard(ctx: Context, dp: Float,
                        title: String, body: String, bg: Int) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background  = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                cornerRadius = m(8, dp).toFloat()
                setColor(bg)
            }
            val p = m(10, dp); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            addView(TextView(ctx).apply {
                text     = title; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#212121"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = m(4, dp) }
            })
            addView(TextView(ctx).apply {
                text = body; textSize = 11f; setTextColor(Color.parseColor("#424242"))
                setLineSpacing(0f, 1.25f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }

    // ════════════════════════════════════════════════════════════════════
    // UI — EXPORT BUTTON
    // ════════════════════════════════════════════════════════════════════

    private fun buildExportButton(ctx: Context, dp: Float, data: ReportData): View {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Export PDF button
        container.addView(Button(ctx).apply {
            text     = "Export Full Report (PDF)"
            textSize = 13f; setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1A237E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, m(50, dp)
            ).also { it.bottomMargin = m(8, dp) }
            setOnClickListener { exportPdf(ctx, data) }
        })

        // Remote Kill Switch card
        container.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF3F3"))
            val border = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#FFF3F3"))
                setStroke(m(1, dp), Color.parseColor("#B71C1C"))
                cornerRadius = m(8, dp).toFloat()
            }
            background = border
            setPadding(m(16, dp), m(14, dp), m(16, dp), m(14, dp))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = m(12, dp) }

            addView(TextView(ctx).apply {
                text = "🚨 Lost or Stolen Device?"
                textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#B71C1C"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = m(6, dp) }
            })

            addView(TextView(ctx).apply {
                text = "This app checks a remote kill switch automatically. " +
                        "If your phone is stolen, lock your vault remotely from " +
                        "any browser — no app needed."
                textSize = 12f
                setTextColor(Color.parseColor("#5D0000"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = m(10, dp) }
            })

            addView(TextView(ctx).apply {
                text = "How to lock remotely:"
                textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#B71C1C"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = m(4, dp) }
            })

            addView(TextView(ctx).apply {
                text = "1. Open the URL below on any device\n" +
                        "2. Change  \"status\": \"ok\"  →  \"status\": \"revoked\"\n" +
                        "3. Save — vault locks within 24 hours"
                textSize = 12f
                setTextColor(Color.parseColor("#5D0000"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = m(10, dp) }
            })

            val revUrl = "https://gist.github.com/NEO7931/ca8812045ee3fcb913f053e6bda995b1"
            addView(TextView(ctx).apply {
                text = revUrl
                textSize = 10f
                setTextColor(Color.parseColor("#1565C0"))
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = m(10, dp) }
            })

            addView(Button(ctx).apply {
                text = "Copy Kill Switch URL"
                textSize = 12f; setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#B71C1C"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, m(42, dp)
                )
                setOnClickListener {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("kill_switch", revUrl))
                    android.widget.Toast.makeText(ctx,
                        "URL copied — open it on another device to lock remotely",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            })
        })

        return container
    }

    // ════════════════════════════════════════════════════════════════════
    // UI — FOOTER
    // ════════════════════════════════════════════════════════════════════

    private fun buildFooter(ctx: Context, dp: Float): View =
        TextView(ctx).apply {
            text      = "CipherLock v1.0  |  New Era University BSIT Capstone 2026  |  For personal use only."
            textSize  = 10f; setTextColor(Color.GRAY); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = m(16, dp) }
        }

    // ════════════════════════════════════════════════════════════════════
    // INNER VIEW — Score Ring  (Canvas arc + centered text)
    // ════════════════════════════════════════════════════════════════════

    inner class ScoreRingView(
        ctx: Context,
        private val score: Int,
        private val ringColor: Int,
        private val dp: Float
    ) : View(ctx) {

        override fun onDraw(canvas: Canvas) {
            val w  = width.toFloat()
            val h  = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f

            val strokeW = dp * 13f
            val margin  = strokeW * 0.75f
            val oval    = RectF(margin, margin, w - margin, h - margin)

            // Background ring
            canvas.drawArc(oval, -90f, 360f, false,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = strokeW
                    color = Color.parseColor("#E0E0E0")
                })

            // Score arc (clockwise from 12 o'clock)
            canvas.drawArc(oval, -90f, score / 100f * 360f, false,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = strokeW
                    strokeCap = Paint.Cap.ROUND; color = ringColor
                })

            // Centered text block: [score] / [/ 100] / [Overall Score]
            val numSize = h * 0.29f
            val subSize = h * 0.10f

            // Approximate block height to center it
            val blockH = numSize + subSize * 3.2f
            val blockTop = cy - blockH / 2f

            canvas.drawText("$score", cx, blockTop + numSize,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER; textSize = numSize
                    color = Color.parseColor("#1A237E"); isFakeBoldText = true
                })
            canvas.drawText("/ 100", cx, blockTop + numSize + subSize * 1.5f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER; textSize = subSize; color = Color.GRAY
                })
            canvas.drawText("Overall Score", cx, blockTop + numSize + subSize * 3.1f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER; textSize = subSize * 0.85f; color = Color.GRAY
                })
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // PDF EXPORT  — saves to Downloads, uses MediaStore on API 29+
    // ════════════════════════════════════════════════════════════════════

    private fun exportPdf(ctx: Context, data: ReportData) {
        try {
            val doc      = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
            val page     = doc.startPage(pageInfo)
            drawPdfPage(page.canvas, data)
            doc.finishPage(page)

            val sdf      = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
            val fileName = "CipherLock_Report_${sdf.format(Date())}.pdf"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, values, null, null)
                    Toast.makeText(ctx, "✅ Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(ctx, "❌ Could not create file", Toast.LENGTH_LONG).show()
                }
            } else {
                @Suppress("DEPRECATION")
                val dir  = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = java.io.File(dir, fileName)
                FileOutputStream(file).use { doc.writeTo(it) }
                Toast.makeText(ctx, "✅ Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
            }
            doc.close()

        } catch (e: Exception) {
            Toast.makeText(ctx, "❌ Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun drawPdfPage(canvas: Canvas, data: ReportData) {
        val navy   = Color.parseColor("#1A237E")
        val black  = Color.BLACK
        val gray   = Color.parseColor("#757575")
        val green  = Color.parseColor("#2E7D32")
        val orange = Color.parseColor("#E65100")
        val sdf    = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())

        fun p(c: Int, sz: Float, bold: Boolean = false) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = c; textSize = sz; isFakeBoldText = bold
            }

        fun rule(y: Float, clr: Int = navy, w: Float = 2f) =
            canvas.drawLine(40f, y, 555f, y, Paint().apply { color = clr; strokeWidth = w })

        var y = 60f

        canvas.drawText("CipherLock Security Report",     40f, y, p(navy, 26f, true)); y += 22f
        canvas.drawText("Generated: ${sdf.format(Date())}  |  Confidential  |  Personal use only",
            40f, y, p(gray, 12f));       y += 18f
        rule(y); y += 28f

        // Score
        canvas.drawText("OVERALL SECURITY SCORE",         40f, y, p(navy, 15f, true)); y += 20f
        canvas.drawText("${data.overallScore} / 100  —  ${data.scoreLabel}",
            40f, y, p(data.scoreColor, 13f, true)); y += 28f
        rule(y, Color.LTGRAY, 1f); y += 18f

        // Stats
        canvas.drawText("STATISTICS",                     40f, y, p(navy, 15f, true)); y += 20f
        canvas.drawText("• Passwords: ${data.totalPasswords} saved, ${data.strongPasswords} strong (${data.passwordStrengthPct}%)",
            40f, y, p(black, 12f));      y += 18f
        canvas.drawText("• Suspicious messages blocked this week: ${data.threatsThisWeek}",
            40f, y, p(black, 12f));      y += 18f
        canvas.drawText("• Help Desk articles read: ${data.lessonsCompleted} / ${data.totalLessons}",
            40f, y, p(black, 12f));      y += 28f
        rule(y, Color.LTGRAY, 1f); y += 18f

        // Recommendations
        canvas.drawText("DETAILED RECOMMENDATIONS",       40f, y, p(navy, 15f, true)); y += 20f

        if (data.reusedPairs.isNotEmpty()) {
            val (a, b) = data.reusedPairs[0]
            canvas.drawText("⚠  Password Reuse: \"$a\" and \"$b\" share the same password.", 40f, y, p(orange, 12f)); y += 18f
        }
        if (data.weakServices.isNotEmpty()) {
            canvas.drawText("⚠  Weak Passwords: ${data.weakServices.take(3).joinToString(", ")}", 40f, y, p(orange, 12f)); y += 18f
        }
        if (data.totalPasswords > 0 && data.weakServices.isEmpty() && data.reusedPairs.isEmpty()) {
            canvas.drawText("✓  All ${data.totalPasswords} vault passwords are strong and unique.", 40f, y, p(green, 12f)); y += 18f
        }
        if (data.totalPasswords == 0) {
            canvas.drawText("•  Vault is empty. Add passwords to get strength analysis.", 40f, y, p(black, 12f)); y += 18f
        }
        val remaining = data.totalLessons - data.lessonsCompleted
        if (remaining > 0) {
            canvas.drawText("•  Read $remaining more Help Desk article${if (remaining > 1) "s" else ""} to earn your Cyber Defender badge.", 40f, y, p(black, 12f))
        } else {
            canvas.drawText("✓  All ${data.totalLessons} security lessons completed. Cyber Defender badge earned!", 40f, y, p(green, 12f))
        }
        y += 18f
        val thr = data.threatsThisWeek
        canvas.drawText("•  $thr suspicious message${if (thr != 1) "s" else ""} detected this week. Always verify before tapping links.", 40f, y, p(black, 12f))

        // Footer
        rule(800f, gray, 1f)
        canvas.drawText("CipherLock v1.0  |  New Era University BSIT Capstone 2026  |  For personal use only.", 40f, 820f, p(gray, 10f))
    }

    // ════════════════════════════════════════════════════════════════════
    // UI HELPERS
    // ════════════════════════════════════════════════════════════════════

    private fun cardBg(dp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = m(12, dp).toFloat()
        setColor(Color.WHITE)
    }

    private fun cardLp(dp: Float) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).also { it.bottomMargin = m(12, dp) }

    // Invisible horizontal gap (for spacing between columns)
    private fun gapH(ctx: Context, sizePx: Int): View =
        View(ctx).apply { layoutParams = LinearLayout.LayoutParams(sizePx, 1) }
}