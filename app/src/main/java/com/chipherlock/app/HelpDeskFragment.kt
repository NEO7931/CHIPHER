package com.chipherlock.app

// ═══════════════════════════════════════════════════════════════════════════
// HelpDeskFragment.kt  — Phase 3.1 (rev 3)
//
// Full chat-style UI with:
//   - RecyclerView message bubbles (user right, assistant left)
//   - Responsive EditText that grows with input (1–4 lines), never pushes layout
//   - Mode selector sheet: OFFLINE (FAQ) or ONLINE (AI) — user chooses
//   - Offline mode: keyword search → shows answer as assistant bubble
//   - Online mode: Groq streaming response → assistant bubble, typing indicator
//   - Conversation history maintained per session (cleared on mode switch)
//   - Lesson tracking unchanged
// ═══════════════════════════════════════════════════════════════════════════

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HelpDeskFragment : Fragment() {

    // ── Coroutines ────────────────────────────────────────────────────────────
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val groqClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private companion object {
        const val GROQ_URL    = "https://api.groq.com/openai/v1/chat/completions"
        const val MODEL       = "llama-3.3-70b-versatile"
        const val MAX_TOKENS  = 400
        const val PREFS_NAME  = "helpdesk_prefs"
        const val KEY_LESSONS = "helpdesk_lessons_read"

        // Message types
        const val TYPE_USER      = 0
        const val TYPE_ASSISTANT = 1
        const val TYPE_TYPING    = 2   // animated "..." indicator
    }

    // ── Data model ───────────────────────────────────────────────────────────
    data class ChatMessage(
        val type: Int,          // TYPE_USER / TYPE_ASSISTANT / TYPE_TYPING
        val text: String = "",
        val isOffline: Boolean = false
    )

    // ── dp helper — class-level so all functions share the same type ────────
    private lateinit var dp: (Int) -> Int

    // ── Tab container ────────────────────────────────────────────────────────
    private lateinit var tabContent: FrameLayout

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var rvChat:         RecyclerView
    private lateinit var etInput:        EditText
    private lateinit var btnSend:        ImageButton
    private lateinit var btnModeToggle:  TextView
    private lateinit var layoutManager:  LinearLayoutManager

    // ── State ─────────────────────────────────────────────────────────────────
    private val messages       = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private var isOnlineMode   = false
    private var groqAvailable  = false
    private var readArticleIds = mutableSetOf<String>()
    // Conversation history for multi-turn Groq context
    private val groqHistory    = mutableListOf<JSONObject>()

    // ════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ════════════════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        migrateLegacyPrefs(ctx)
        loadReadSet(ctx)
        groqAvailable = BuildConfig.GROQ_API_KEY.isNotBlank() &&
                !BuildConfig.GROQ_API_KEY.startsWith("PASTE_")
        isOnlineMode = groqAvailable

        val density = ctx.resources.displayMetrics.density
        dp = { v -> (v * density).toInt() }

        // ── Outer shell: header + tabs + content ──────────────────────────────
        val shell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Header
        shell.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF0D47A1.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))

            addView(TextView(ctx).apply {
                text     = "🛡️"
                textSize = 20f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text     = "Help Desk"
                    textSize = 18f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(0xFFFFFFFF.toInt())
                })
                addView(TextView(ctx).apply {
                    text     = "CHIPHERLOCK Security Assistant"
                    textSize = 11f
                    setTextColor(0xFFB3E5FC.toInt())
                })
            })
        })

        // TabLayout
        val tabLayout = TabLayout(ctx).apply {
            setBackgroundColor(0xFF1565C0.toInt())
            setTabTextColors(0xFFB3E5FC.toInt(), 0xFFFFFFFF.toInt())
            setSelectedTabIndicatorColor(0xFFFFFFFF.toInt())
            tabMode = TabLayout.MODE_FIXED
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        tabLayout.addTab(tabLayout.newTab().setText("🏠  Home"))
        tabLayout.addTab(tabLayout.newTab().setText("💬  Chat"))
        shell.addView(tabLayout)

        // Content switcher
        tabContent = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        shell.addView(tabContent)

        // Tab listener only — initial load happens in onViewCreated
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showHomeTab(ctx)
                    1 -> showChatTab(ctx)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        return shell
    }

    private fun showHomeTab(ctx: Context) {
        if (tabContent.id == View.NO_ID) {
            tabContent.id = View.generateViewId()
        }
        tabContent.removeAllViews()
        val homeFrag = HelpDeskHomeFragment()
        childFragmentManager.beginTransaction()
            .replace(tabContent.id, homeFrag)
            .commitAllowingStateLoss()
    }

    private fun showChatTab(ctx: Context) {
        tabContent.removeAllViews()
        val chatView = buildUI(ctx)
        tabContent.addView(chatView)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Safe to do fragment transactions here — fragment is fully attached
        showHomeTab(requireContext())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.coroutineContext.cancelChildren()
    }

    // ════════════════════════════════════════════════════════════════════════
    // UI BUILD
    // ════════════════════════════════════════════════════════════════════════

    private fun buildUI(ctx: Context): View {
        // Header is now in the outer shell (onCreateView).
        // This function builds only the CHAT tab content.
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ── Mode toggle bar ───────────────────────────────────────────────────
        root.addView(buildModeBar(ctx))

        // ── 3. RecyclerView — fills all remaining space ───────────────────────
        layoutManager = LinearLayoutManager(ctx).apply {
            stackFromEnd = true   // new messages appear at bottom
        }
        adapter = ChatAdapter(ctx, messages)

        rvChat = RecyclerView(ctx).apply {
            this.layoutManager = this@HelpDeskFragment.layoutManager
            this.adapter       = this@HelpDeskFragment.adapter
            setBackgroundColor(0xFFF5F5F5.toInt())
            setPadding(0, dp(8), 0, dp(8))
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(rvChat)

        // ── 4. Input bar — anchored to bottom ────────────────────────────────
        root.addView(buildInputBar(ctx))

        // ── Welcome message ───────────────────────────────────────────────────
        postWelcome()

        return root
    }

    // ── Mode bar ─────────────────────────────────────────────────────────────
    private fun buildModeBar(ctx: Context): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFE8EAF6.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        val labelView = TextView(ctx).apply {
            text     = if (isOnlineMode) "Mode: 🤖 AI Assistant" else "Mode: 📖 Offline FAQ"
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF1A237E.toInt())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(labelView)

        btnModeToggle = TextView(ctx).apply {
            text     = if (isOnlineMode) "Switch to FAQ" else "Switch to AI"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF3949AB.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                if (!isOnlineMode && !groqAvailable) {
                    Toast.makeText(ctx, "AI not available no API key configured.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                isOnlineMode = !isOnlineMode
                groqHistory.clear()
                messages.clear()
                adapter.notifyDataSetChanged()
                labelView.text = if (isOnlineMode) "Mode: 🤖 AI Assistant" else "Mode: 📖 Offline FAQ"
                text           = if (isOnlineMode) "Switch to FAQ" else "Switch to AI"
                postWelcome()
            }
        }
        bar.addView(btnModeToggle)

        return bar
    }

    // ── Input bar ─────────────────────────────────────────────────────────────
    private fun buildInputBar(ctx: Context): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.BOTTOM    // aligns send button to bottom when text grows
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            elevation = dp(4).toFloat()
        }

        etInput = EditText(ctx).apply {
            hint        = "Ask a question…"
            textSize    = 15f
            minLines    = 1
            maxLines    = 4    // grows up to 4 lines, then scrolls internally
            isSingleLine = false
            imeOptions  = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFFF0F0F0.toInt())
            background  = buildRoundedBackground(ctx, 0xFFF0F0F0.toInt(), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = dp(8) }
        }
        bar.addView(etInput)

        btnSend = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setBackgroundColor(0xFF1565C0.toInt())
            background = buildRoundedBackground(ctx, 0xFF1565C0.toInt(), dp(22))
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            val size = dp(44)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { onSendTapped(ctx) }
        }
        bar.addView(btnSend)

        return bar
    }

    // Programmatic rounded background drawable
    private fun buildRoundedBackground(ctx: Context, color: Int, radius: Int)
            : android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // CHAT LOGIC
    // ════════════════════════════════════════════════════════════════════════

    private fun postWelcome() {
        val welcome = if (isOnlineMode)
            "👋 Hi! I'm your CHIPHERLOCK AI assistant.\n\n" +
                    "Ask me anything about the app or about scam protection in the Philippines. " +
                    "I'll answer in whatever language you use Filipino, Bisaya, Ilocano, Taglish, English, anything."
        else
            "👋 Hi! I'm your offline Help Desk.\n\n" +
                    "Type a question and I'll search our knowledge base, or tap a topic below to browse articles.\n\n" +
                    "📚 Topics: SMS Shield · Vault · Network · Report · Security Tips · App Help"

        addMessage(ChatMessage(TYPE_ASSISTANT, welcome, isOffline = true))
    }

    private fun onSendTapped(ctx: Context) {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return

        // Hide keyboard
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etInput.windowToken, 0)

        etInput.text.clear()
        addMessage(ChatMessage(TYPE_USER, text))

        if (isOnlineMode) handleOnline(ctx, text)
        else              handleOffline(ctx, text)
    }

    // OFFLINE: keyword search → assistant bubble
    private fun handleOffline(ctx: Context, question: String) {
        val match = HelpDeskKnowledgeBase.search(question)
        if (match != null) {
            addMessage(ChatMessage(TYPE_ASSISTANT,
                "📖  ${match.title}\n\n${match.body}", isOffline = true))
            markArticleRead(ctx, match.id)
        } else {
            addMessage(ChatMessage(TYPE_ASSISTANT,
                "❓  I couldn't find an article that matches your question.\n\n" +
                        "Try rephrasing, or switch to AI mode for a freeform answer.", isOffline = true))
        }
    }

    // ONLINE: Groq with typing indicator → replace with real answer
    private fun handleOnline(ctx: Context, question: String) {
        // Add typing indicator bubble
        val typingIndex = messages.size
        addMessage(ChatMessage(TYPE_TYPING))

        // Add to Groq conversation history
        groqHistory.add(JSONObject().apply {
            put("role",    "user")
            put("content", question)
        })

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                callGroq(BuildConfig.GROQ_API_KEY, groqHistory)
            }

            // Replace typing indicator with real response
            if (typingIndex < messages.size) {
                when (result) {
                    is GroqResult.Success -> {
                        messages[typingIndex] = ChatMessage(TYPE_ASSISTANT, result.text)
                        groqHistory.add(JSONObject().apply {
                            put("role",    "assistant")
                            put("content", result.text)
                        })
                    }
                    is GroqResult.Failure -> {
                        // Fall back to offline
                        val match = HelpDeskKnowledgeBase.search(question)
                        val fallbackText = if (match != null)
                            "⚠️  AI unavailable  here's what I found offline:\n\n📖  ${match.title}\n\n${match.body}"
                        else
                            "⚠️  AI unavailable and no offline match found.\n\nTry rephrasing, or switch to FAQ mode."
                        messages[typingIndex] = ChatMessage(TYPE_ASSISTANT, fallbackText, isOffline = true)
                        if (match != null) markArticleRead(ctx, match.id)
                    }
                }
                adapter.notifyItemChanged(typingIndex)
                scrollToBottom()
            }
        }
    }

    private fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        adapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        rvChat.post { rvChat.smoothScrollToPosition(messages.size - 1) }
    }

    // ════════════════════════════════════════════════════════════════════════
    // RECYCLERVIEW ADAPTER
    // ════════════════════════════════════════════════════════════════════════

    inner class ChatAdapter(
        private val ctx:      Context,
        private val data:     List<ChatMessage>
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int) = data[position].type

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                TYPE_USER      -> UserVH(buildUserBubble())
                TYPE_TYPING    -> TypingVH(buildTypingBubble())
                else           -> AssistantVH(buildAssistantBubble())
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val msg = data[position]
            when (holder) {
                is UserVH      -> holder.tv.text = msg.text
                is AssistantVH -> {
                    holder.tv.text = msg.text
                    holder.card.setBackgroundColor(
                        if (msg.isOffline) 0xFFE8F5E9.toInt() else 0xFFFFFFFF.toInt()
                    )
                }
                is TypingVH    -> { /* static animation dots */ }
            }
        }

        override fun getItemCount() = data.size

        // ── ViewHolders ───────────────────────────────────────────────────────

        inner class UserVH(view: View) : RecyclerView.ViewHolder(view) {
            val tv: TextView = view.findViewWithTag("bubble_text")
        }

        inner class AssistantVH(view: View) : RecyclerView.ViewHolder(view) {
            val tv:   TextView    = view.findViewWithTag("bubble_text")
            val card: LinearLayout = view.findViewWithTag("bubble_card")
        }

        inner class TypingVH(view: View) : RecyclerView.ViewHolder(view)

        // ── Bubble builders ───────────────────────────────────────────────────

        private fun buildUserBubble(): View {
            // Right-aligned, blue bubble
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.END
                setPadding(dp(48), dp(4), dp(12), dp(4))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val bubble = TextView(ctx).apply {
                tag      = "bubble_text"
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                background = buildRoundedBackground(ctx, 0xFF1565C0.toInt(), dp(18))
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(bubble)
            return row
        }

        private fun buildAssistantBubble(): View {
            // Left-aligned, white/green card
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.START
                setPadding(dp(12), dp(4), dp(48), dp(4))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            // Avatar dot
            row.addView(TextView(ctx).apply {
                text     = "🛡"
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(8)
                    topMargin = dp(4)
                }
            })

            val card = LinearLayout(ctx).apply {
                tag         = "bubble_card"
                orientation = LinearLayout.VERTICAL
                background  = buildRoundedBackground(ctx, 0xFFFFFFFF.toInt(), dp(18))
                setPadding(dp(14), dp(10), dp(14), dp(10))
                elevation   = dp(1).toFloat()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val tv = TextView(ctx).apply {
                tag      = "bubble_text"
                textSize = 15f
                setTextColor(0xFF212121.toInt())
                setTextIsSelectable(true)
            }
            card.addView(tv)
            row.addView(card)
            return row
        }

        private fun buildTypingBubble(): View {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.START
                setPadding(dp(12), dp(4), dp(48), dp(4))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(TextView(ctx).apply {
                text     = "🛡"
                textSize = 18f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8); topMargin = dp(4) }
            })
            row.addView(TextView(ctx).apply {
                text       = "  ●  ●  ●  "
                textSize   = 18f
                setTextColor(0xFF9E9E9E.toInt())
                background = buildRoundedBackground(ctx, 0xFFEEEEEE.toInt(), dp(18))
                setPadding(dp(14), dp(10), dp(14), dp(10))
            })
            return row
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // GROQ API
    // ════════════════════════════════════════════════════════════════════════

    private sealed class GroqResult {
        data class Success(val text: String) : GroqResult()
        data class Failure(val reason: String) : GroqResult()
    }

    private fun callGroq(apiKey: String, history: List<JSONObject>): GroqResult {
        return try {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role",    "system")
                    put("content", buildSystemPrompt())
                })
                history.forEach { put(it) }
            }

            val body = JSONObject().apply {
                put("model",       MODEL)
                put("max_tokens",  MAX_TOKENS)
                put("temperature", 0.4)
                put("messages",    messages)
            }.toString()

            val request = Request.Builder()
                .url(GROQ_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type",  "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            groqClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: return GroqResult.Failure("Empty body")
                if (!response.isSuccessful) return GroqResult.Failure("HTTP ${response.code}")
                val text = JSONObject(bodyStr)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                GroqResult.Success(text)
            }
        } catch (e: Exception) {
            GroqResult.Failure(e.message ?: "Unknown")
        }
    }

    private fun buildSystemPrompt(): String = """
        You are the CHIPHERLOCK Help Desk assistant a friendly security guide inside the CHIPHERLOCK Android app for Filipino users.

        CHIPHERLOCK features:
        - SMS scam scanner (AI + keyword rules, 15 PH scam categories)
        - Encrypted password vault (AES-256, biometric unlock, decoy vault)
        - WiFi threat scanner (evil twin, suspicious SSID detection)
        - Protection against GCash, BDO, BPI, Maya, and other PH financial fraud
        - Canary credential system, remote kill switch

        LANGUAGE RULE (CRITICAL):
        Always respond in the EXACT language or mix the user wrote in.
        Supported: Filipino, Tagalog, Cebuano/Bisaya, Ilocano, Hiligaynon/Ilonggo,
        Kapampangan, Waray, Pangasinense, Bikol, English, Taglish  any mix.

        NON-TECHNICAL USER RULE:
        Assume zero technical background. Never use jargon without a plain analogy.
        Format answers as:
          1. One sentence: what happened (with analogy if technical)
          2. One sentence: is the user at fault/at risk?
          3. Up to 4 numbered steps: what to do right now
          4. One sentence: what to watch for going forward

        ANALOGY LIBRARY:
        SQL injection → "Parang may nadiskubreng butas sa pinto ng isang website"
        Password breach → "Parang nakopya ng ibang tao ang susi mo"
        Phishing → "Parang may nagpanggap na courier para makuha ang iyong address"
        Malware → "Parang may nakapasok na hindi bisita sa iyong telepono"
        Evil twin WiFi → "Parang may pekeng tindahan sa tabi ng tunay, para makuha ang iyong pera"

        ACCURACY RULE:
        If the threat is server-side (SQL injection, breach), say clearly the user cannot prevent it on their device.
        Do NOT mix unrelated advice into the answer.

        SCOPE RULE:
        Only answer questions about CHIPHERLOCK, SMS scams, passwords, WiFi safety, and PH online security.
        Off-topic: reply in the user's language  "Hindi ko masagot iyan  para lang ako sa tulong ukol sa CHIPHERLOCK at digital security."

        TONE: Friendly, warm, direct. Like a trusted tech-savvy friend.
    """.trimIndent()

    // ════════════════════════════════════════════════════════════════════════
    // LESSON TRACKING
    // ════════════════════════════════════════════════════════════════════════

    private fun loadReadSet(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        readArticleIds = (prefs.getString("read_ids", "") ?: "")
            .split(",").filter { it.isNotBlank() }.toMutableSet()
    }

    private fun markArticleRead(ctx: Context, id: String) {
        if (id in readArticleIds) return
        readArticleIds.add(id)
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("read_ids", readArticleIds.joinToString(","))
            .putInt(KEY_LESSONS, readArticleIds.size)
            .apply()
    }

    private fun migrateLegacyPrefs(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("migration_done", false)) return
        prefs.edit()
            .putInt(KEY_LESSONS, 0)
            .putString("read_ids", "")
            .putBoolean("migration_done", true)
            .apply()
    }
}