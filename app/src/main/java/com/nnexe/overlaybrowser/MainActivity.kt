package com.nnexe.overlaybrowser

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Setup screen: permissions, start/stop overlay console, target host/port, and opacity. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var permOverlay: TextView
    private lateinit var btnPermOverlay: Button
    private lateinit var permNotif: TextView
    private lateinit var btnPermNotif: Button
    private lateinit var targetEndpoint: EditText
    private var requestingPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        permOverlay = findViewById(R.id.perm_overlay)
        btnPermOverlay = findViewById(R.id.btn_perm_overlay)
        permNotif = findViewById(R.id.perm_notif)
        btnPermNotif = findViewById(R.id.btn_perm_notif)
        targetEndpoint = findViewById(R.id.home_url)

        findViewById<Button>(R.id.btn_start).setOnClickListener { startOverlay() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { OverlayService.stop(this) }
        btnPermOverlay.setOnClickListener {
            requestingPermission = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btnPermNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestingPermission = true
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
        }

        // Reuse the existing home_url / btn_save_home views in activity_main.xml for HOST:PORT config
        targetEndpoint.hint = "${Prefs.DEFAULT_HOST}:${Prefs.DEFAULT_PORT}"
        targetEndpoint.setText("${prefs.host}:${prefs.port}")
        findViewById<Button>(R.id.btn_save_home).setOnClickListener {
            val raw = targetEndpoint.text.toString().trim()
            val parts = raw.split(":")
            val newHost = parts.getOrNull(0)?.trim().orEmpty().ifEmpty { Prefs.DEFAULT_HOST }
            val newPort = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: Prefs.DEFAULT_PORT
            prefs.host = newHost
            prefs.port = newPort
            targetEndpoint.setText("${prefs.host}:${prefs.port}")
            Toast.makeText(this, "Saved ${prefs.host}:${prefs.port}", Toast.LENGTH_SHORT).show()
        }

        val opacityValue = findViewById<TextView>(R.id.opacity_value)
        val opacitySeek = findViewById<SeekBar>(R.id.opacity_seek)
        opacitySeek.progress = (prefs.opacity * 100).roundToInt()
        opacityValue.text = getString(R.string.percent, opacitySeek.progress)
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                opacityValue.text = getString(R.string.percent, progress)
                if (!fromUser) return
                prefs.opacity = progress / 100f
                OverlayService.refresh(this@MainActivity)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        // Hide Brave-specific views from the original layout if present
        listOf(
            R.id.brave_status,
            R.id.freeform_status,
            R.id.btn_get_brave,
            R.id.btn_test_brave,
        ).forEach { id ->
            findViewById<View?>(id)?.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        requestingPermission = false
        refresh()
    }

    override fun onStop() {
        super.onStop()
        if (OverlayService.isRunning && !requestingPermission && !isChangingConfigurations) {
            finishAndRemoveTask()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        requestingPermission = false
        if (requestCode == REQ_NOTIF && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 33 && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                requestingPermission = true
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                )
            }
        }
        refresh()
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.no_overlay_permission, Toast.LENGTH_LONG).show()
            btnPermOverlay.performClick()
            return
        }
        OverlayService.show(this)
        finishAndRemoveTask()
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        permOverlay.setText(if (overlayOk) R.string.perm_overlay_ok else R.string.perm_overlay_missing)
        btnPermOverlay.visibility = if (overlayOk) View.GONE else View.VISIBLE

        val notifOk = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        permNotif.setText(if (notifOk) R.string.perm_notif_ok else R.string.perm_notif_missing)
        btnPermNotif.visibility = if (notifOk) View.GONE else View.VISIBLE
    }

    private companion object {
        const val REQ_NOTIF = 1
    }
}
