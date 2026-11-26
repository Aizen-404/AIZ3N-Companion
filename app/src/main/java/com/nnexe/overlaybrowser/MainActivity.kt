
package com.nnexe.overlaybrowser

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var permOverlay: TextView
    private lateinit var btnPermOverlay: Button
    private lateinit var permNotif: TextView
    private lateinit var btnPermNotif: Button
    private lateinit var permStorage: TextView
    private lateinit var btnPermStorage: Button
    private lateinit var targetEndpoint: EditText
    private var requestingPermission = false
    private var pickingFile = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        permOverlay = findViewById(R.id.perm_overlay)
        btnPermOverlay = findViewById(R.id.btn_perm_overlay)
        permNotif = findViewById(R.id.perm_notif)
        btnPermNotif = findViewById(R.id.btn_perm_notif)
        targetEndpoint = findViewById(R.id.home_url)

        permStorage = TextView(this).apply {
            textSize = 14f
            setPadding(0, 12, 0, 4)
        }

        btnPermStorage = Button(this).apply {
            text = "Grant Storage Access"
            setOnClickListener { requestStoragePermission() }
        }

        val parent = targetEndpoint.parent as? LinearLayout
        if (parent != null) {
            val index = parent.indexOfChild(targetEndpoint)
            parent.addView(permStorage, index + 1)
            parent.addView(btnPermStorage, index + 2)
        } else {
            (window.decorView as? android.view.ViewGroup)?.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(permStorage)
                    addView(btnPermStorage)
                }
            )
        }

        findViewById<Button>(R.id.btn_start).setOnClickListener { startOverlay() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            OverlayService.stop(this)
        }

        btnPermOverlay.setOnClickListener {
            requestingPermission = true
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        btnPermNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestingPermission = true
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIF
                )
            }
        }

        targetEndpoint.hint = "${Prefs.DEFAULT_HOST}:${Prefs.DEFAULT_PORT}"
        targetEndpoint.setText("${prefs.host}:${prefs.port}")

        findViewById<Button>(R.id.btn_save_home).setOnClickListener {
            val raw = targetEndpoint.text.toString().trim()
            val parts = raw.split(":")
            val newHost = parts.getOrNull(0)?.trim()
                .orEmpty().ifEmpty { Prefs.DEFAULT_HOST }
            val newPort = parts.getOrNull(1)?.trim()?.toIntOrNull()
                ?: Prefs.DEFAULT_PORT

            if (newPort !in 1..65535) {
                Toast.makeText(this, "Invalid port", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.host = newHost
            prefs.port = newPort
            targetEndpoint.setText("${prefs.host}:${prefs.port}")
            Toast.makeText(
                this,
                "Saved ${prefs.host}:${prefs.port}",
                Toast.LENGTH_SHORT
            ).show()
        }

        val opacityValue = findViewById<TextView>(R.id.opacity_value)
        val opacitySeek = findViewById<SeekBar>(R.id.opacity_seek)

        opacitySeek.progress = (prefs.opacity * 100).roundToInt()
        opacityValue.text = getString(R.string.percent, opacitySeek.progress)

        opacitySeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    opacityValue.text = getString(R.string.percent, progress)
                    if (!fromUser) return
                    prefs.opacity = progress / 100f
                    OverlayService.refresh(this@MainActivity)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            }
        )

        listOf(
            R.id.brave_status,
            R.id.freeform_status,
            R.id.btn_get_brave,
            R.id.btn_test_brave
        ).forEach { id ->
            findViewById<View?>(id)?.visibility = View.GONE
        }

        handlePickerIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePickerIntent(intent)
    }

    private fun handlePickerIntent(source: Intent?) {
        if (source?.action != ACTION_PICK_FILE) return
        requestingPermission = true
        pickingFile = true
        try {
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("text/javascript", "application/javascript", "text/plain")
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(picker, REQ_FILE)
        } catch (_: Exception) {
            requestingPermission = false
            pickingFile = false
            sendPickedFile(null)
            finishAndRemoveTask()
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE) return
        val uri = if (resultCode == RESULT_OK) data?.data else null
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
        sendPickedFile(uri)
        requestingPermission = false
        pickingFile = false
        finishAndRemoveTask()
    }

    private fun sendPickedFile(uri: Uri?) {
        sendBroadcast(
            Intent(ACTION_FILE_PICKED)
                .setPackage(packageName)
                .putExtra(EXTRA_FILE_URI, uri?.toString())
        )
    }

    override fun onResume() {
        super.onResume()
        if (!pickingFile) requestingPermission = false
        refresh()
    }

    override fun onStop() {
        super.onStop()
        if (
            OverlayService.isRunning &&
            !requestingPermission &&
            !isChangingConfigurations
        ) {
            finishAndRemoveTask()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        requestingPermission = false

        if (
            requestCode == REQ_NOTIF &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            if (
                Build.VERSION.SDK_INT >= 33 &&
                !shouldShowRequestPermissionRationale(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            ) {
                requestingPermission = true
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        }

        refresh()
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                refresh()
                return
            }

            try {
                requestingPermission = true
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                requestingPermission = true
                startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                )
            }
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                REQ_STORAGE
            )
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                R.string.no_overlay_permission,
                Toast.LENGTH_LONG
            ).show()
            btnPermOverlay.performClick()
            return
        }

        if (!hasStoragePermission()) {
            Toast.makeText(
                this,
                "Grant storage access first",
                Toast.LENGTH_LONG
            ).show()
            requestStoragePermission()
            return
        }

        OverlayService.show(this)
        finishAndRemoveTask()
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        permOverlay.setText(
            if (overlayOk) R.string.perm_overlay_ok
            else R.string.perm_overlay_missing
        )
        btnPermOverlay.visibility =
            if (overlayOk) View.GONE else View.VISIBLE

        val notifOk = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

        permNotif.setText(
            if (notifOk) R.string.perm_notif_ok
            else R.string.perm_notif_missing
        )
        btnPermNotif.visibility =
            if (notifOk) View.GONE else View.VISIBLE

        val storageOk = hasStoragePermission()
        permStorage.text = if (storageOk) {
            "Storage access: Granted"
        } else {
            "Storage access: Not granted"
        }

        permStorage.setTextColor(
            if (storageOk) 0xFF2EAD65.toInt()
            else 0xFFD94A4A.toInt()
        )

        btnPermStorage.visibility =
            if (storageOk) View.GONE else View.VISIBLE
    }

    companion object {
        const val ACTION_PICK_FILE = "com.nnexe.overlaybrowser.PICK_AGENT_FILE"
        const val ACTION_FILE_PICKED = "com.nnexe.overlaybrowser.AGENT_FILE_PICKED"
        const val EXTRA_FILE_URI = "agent_file_uri"
        private const val REQ_NOTIF = 1
        private const val REQ_STORAGE = 2
        private const val REQ_FILE = 3
    }
}
