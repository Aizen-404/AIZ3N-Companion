package com.nnexe.overlaybrowser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/** Entry point for "Share -> Open in floating console" to send shared text as a command. */
class OpenInOverlayActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (Settings.canDrawOverlays(this)) {
            OverlayService.show(this, command)
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
