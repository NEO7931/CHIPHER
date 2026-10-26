package com.chipherlock.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.min

/** Shared vault modal styling aligned with the CHIPHERLOCK dark theme. */
object VaultDialogUi {

    fun builder(context: Context): MaterialAlertDialogBuilder =
        MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_CHIPHERLOCK_Dialog)

    fun showStyled(dialog: AlertDialog) {
        dialog.window?.setWindowAnimations(R.style.Animation_CHIPHERLOCK_Dialog)
        dialog.show()
        val density = dialog.context.resources.displayMetrics.density
        val side = (density * 28).toInt()
        val maxW = (density * 400).toInt()
        dialog.window?.apply {
            setBackgroundDrawableResource(R.drawable.bg_dialog)
            setDimAmount(0.32f)
            setElevation(density * 18f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                setBackgroundBlurRadius((density * 32).toInt())
                attributes = attributes.apply { blurBehindRadius = (density * 28).toInt() }
            }
            setLayout(
                min(dialog.context.resources.displayMetrics.widthPixels - side * 2, maxW),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(CipherPalette.primary)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(CipherPalette.textSecondary)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(CipherPalette.textSecondary)
    }

    fun bindAppIcon(pm: PackageManager, packageName: String, imageView: ImageView) {
        if (packageName.isBlank()) {
            imageView.setImageResource(R.drawable.ic_vault)
            imageView.imageTintList =
                android.content.res.ColorStateList.valueOf(CipherPalette.textMuted)
            return
        }
        try {
            imageView.imageTintList = null
            imageView.setImageDrawable(pm.getApplicationIcon(packageName))
        } catch (_: PackageManager.NameNotFoundException) {
            imageView.setImageResource(R.drawable.ic_vault)
            imageView.imageTintList =
                android.content.res.ColorStateList.valueOf(CipherPalette.primary)
        }
    }
}
