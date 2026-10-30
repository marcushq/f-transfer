package dev.fujitransfer

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/**
 * Android 16 "Live Updates": an ongoing notification that asks to be promoted shows as a chip
 * on the status bar and on the lock screen — the system's own Dynamic-Island-like surface,
 * which skins such as vivo's Origin Island build on. On Android 16 [TransferService] uses
 * Notification.ProgressStyle directly; [extras] carries the same request for the compat
 * notification on older systems, where it is simply ignored.
 */
object LiveUpdates {
    /** Extras asking for promotion, with [chip] (a few characters, e.g. "3/12") on the status bar. */
    fun extras(chip: String?) = Bundle().apply {
        putBoolean("android.requestPromotedOngoing", true)
        if (chip != null) putString("android.shortCriticalText", chip)
    }

    /** Android 16+, and the user hasn't switched Live Updates off for this app. */
    fun available(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 36 && context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()

    /**
     * Whether the phone's own status bar actually shows promoted notifications. Android can
     * accept a Live Update without the skin drawing it: on a vivo (OriginOS 6, 29 Oct) the log
     * read promotable, allowed and promoted, yet no island appeared. So "Tự động" trusts it only
     * on skins known to draw Live Updates (Pixel's, Samsung's Now Bar) and uses the floating
     * window elsewhere.
     */
    fun shownBySystem(context: Context): Boolean =
        available(context) && Build.MANUFACTURER.lowercase() in setOf("google", "samsung")

    /** Android 16 with Live Updates switched off for this app: they can be switched back on. */
    fun canEnable(context: Context) = Build.VERSION.SDK_INT >= 36 && !available(context)

    /** The system page with this app's Live Updates switch. */
    fun settingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 36) {
            Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
