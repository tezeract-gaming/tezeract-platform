package com.tezeract.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts Motion Engine foreground service on device boot.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed — starting MotionEngineService")
            val serviceIntent = Intent(context, MotionEngineService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
