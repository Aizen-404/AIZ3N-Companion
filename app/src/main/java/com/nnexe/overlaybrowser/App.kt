package com.nnexe.overlaybrowser

import android.app.Application
import android.os.Looper
import android.util.Log
import android.view.WindowManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        installDialogCrashGuard()
    }

    private fun installDialogCrashGuard() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (thread === Looper.getMainLooper().thread && isBadToken(throwable)) {
                Log.w(TAG, "Ignored a pop-up that needs an Activity", throwable)
                while (true) {
                    try {
                        Looper.loop()
                    } catch (e: Throwable) {
                        if (isBadToken(e)) {
                            Log.w(TAG, "Ignored a pop-up that needs an Activity", e)
                        } else {
                            defaultHandler?.uncaughtException(thread, e)
                            return@setDefaultUncaughtExceptionHandler
                        }
                    }
                }
            } else {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun isBadToken(t: Throwable?): Boolean {
        var cur = t
        while (cur != null) {
            if (cur is WindowManager.BadTokenException) return true
            cur = cur.cause
        }
        return false
    }

    companion object {
        const val TAG = "OverlayConsole"
    }
}
