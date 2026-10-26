package com.chipherlock.app

import android.app.assist.AssistStructure
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.*
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews

// NOTE: No @RequiresApi on the class — Android needs to instantiate this
// service at the system level; a class-level annotation can block binding.
class ChipherLockAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            callback.onSuccess(null)
            return
        }

        val structure = request.fillContexts.lastOrNull()?.structure
            ?: return callback.onSuccess(null)

        val parsed = parseStructure(structure)
        if (parsed.passwordId == null) {
            callback.onSuccess(null)
            return
        }

        val callingPackage = structure.activityComponent?.packageName
            ?: return callback.onSuccess(null)

        val matches = findMatches(callingPackage)
        if (matches.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        val responseBuilder = FillResponse.Builder()

        matches.forEach { entry ->
            val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_2).apply {
                setTextViewText(android.R.id.text1, "${entry.service}")
                setTextViewText(android.R.id.text2, entry.username.ifEmpty { "Tap to fill" })
            }

            @Suppress("DEPRECATION")
            val datasetBuilder = Dataset.Builder(presentation)

            parsed.usernameId?.let { uid ->
                @Suppress("DEPRECATION")
                datasetBuilder.setValue(uid, AutofillValue.forText(entry.username))
            }

            // ── REM-001: build the AutofillValue from a CharArray, then wipe.
            @Suppress("DEPRECATION")
            datasetBuilder.setValue(
                parsed.passwordId,
                AutofillValue.forText(java.nio.CharBuffer.wrap(entry.password))
            )

            try {
                responseBuilder.addDataset(datasetBuilder.build())
            } catch (_: Exception) { /* skip malformed dataset */ }

            // ── Layer 2 (Canary): fire alert if this entry is a honeytoken.
            if (entry.isCanary) {
                CanaryAlert.fire(this, entry.service, callingPackage)
            }

            // ── REM-001: zero the CharArray now that the value has been handed off.
            entry.wipe()
        }

        callback.onSuccess(responseBuilder.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onSuccess()
    }

    // ── Structure parser ──────────────────────────────────────────────────────

    private data class ParsedFields(
        val usernameId: AutofillId?,
        val passwordId: AutofillId?
    )

    private fun parseStructure(structure: AssistStructure): ParsedFields {
        var usernameId: AutofillId? = null
        var passwordId: AutofillId? = null

        fun traverseNode(node: AssistStructure.ViewNode) {
            val hints     = node.autofillHints ?: emptyArray()
            val inputType = node.inputType

            val isPassword = hints.any { it.contains("password", ignoreCase = true) }
                    || (inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
                    || (inputType and android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) != 0
                    || node.idEntry?.contains("pass", ignoreCase = true) == true
                    || node.hint?.contains("password", ignoreCase = true) == true

            val isUsername = hints.any {
                it.contains("username", ignoreCase = true) ||
                        it.contains("email",    ignoreCase = true)
            } || node.idEntry?.let {
                it.contains("email", ignoreCase = true) ||
                        it.contains("user",  ignoreCase = true) ||
                        it.contains("login", ignoreCase = true)
            } == true || node.hint?.let {
                it.contains("email",    ignoreCase = true) ||
                        it.contains("username", ignoreCase = true)
            } == true

            if      (isPassword && passwordId == null) passwordId = node.autofillId
            else if (isUsername && usernameId == null) usernameId = node.autofillId

            for (i in 0 until node.childCount) traverseNode(node.getChildAt(i))
        }

        for (i in 0 until structure.windowNodeCount) {
            traverseNode(structure.getWindowNodeAt(i).rootViewNode)
        }

        return ParsedFields(usernameId, passwordId)
    }

    // ── Match vault entries to requesting app ─────────────────────────────────

    private fun findMatches(callingPackage: String): List<PasswordEntry> {
        val all = VaultFragment.loadEntriesStatic(this)

        // 1. Exact package name match (most reliable)
        val exact = all.filter {
            it.packageName.isNotEmpty() &&
                    it.packageName.equals(callingPackage, ignoreCase = true)
        }
        if (exact.isNotEmpty()) return exact

        // 2. Fuzzy: service name appears inside the package name
        return all.filter { entry ->
            entry.service.isNotEmpty() &&
                    callingPackage.contains(
                        entry.service.lowercase().replace(" ", ""),
                        ignoreCase = true
                    )
        }
    }
}