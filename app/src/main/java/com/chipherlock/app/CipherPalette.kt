package com.chipherlock.app

import android.graphics.Color

/**
 * Shared colors for views that are still created dynamically. XML screens use
 * the matching resources in colors.xml.
 */
object CipherPalette {
    val background: Int = Color.parseColor("#071018")
    val surface: Int = Color.parseColor("#0D1924")
    val surfaceHigh: Int = Color.parseColor("#142432")
    val outline: Int = Color.parseColor("#2C4557")
    val outlineSoft: Int = Color.parseColor("#1D3342")
    val primary: Int = Color.parseColor("#35D6C1")
    val primaryContainer: Int = Color.parseColor("#123D3C")
    val onPrimary: Int = Color.parseColor("#00201C")
    val textPrimary: Int = Color.parseColor("#E9F4F7")
    val textSecondary: Int = Color.parseColor("#A9BBC5")
    val textMuted: Int = Color.parseColor("#738A97")
    val textDisabled: Int = Color.parseColor("#80A9BBC5")
    val success: Int = Color.parseColor("#69D391")
    val successContainer: Int = Color.parseColor("#163B2A")
    val warning: Int = Color.parseColor("#F5C451")
    val warningContainer: Int = Color.parseColor("#493A14")
    val danger: Int = Color.parseColor("#FF6B7A")
    val dangerContainer: Int = Color.parseColor("#4B2029")
    val info: Int = Color.parseColor("#62B5FF")
    val infoContainer: Int = Color.parseColor("#153A57")
}
