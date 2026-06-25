package com.example.bluemesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.example.bluemesh.data.DefaultDataRepository
import com.example.bluemesh.data.models.ConnectionStatus

class BlueMeshForegroundService : Service() {

    companion object {
        const val TAG = "BlueMesh-FGS"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "bluemesh_ble_channel"
    }

    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        try {
            startForegroundService()
            isRunning = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        if (!isRunning) {
            try {
                startForegroundService()
                isRunning = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int) {
        Log.w(TAG, "onTimeout(startId=$startId)")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isRunning = false
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "onTimeout(startId=$startId, fgsType=$fgsType)")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        isRunning = false
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        isRunning = false
        super.onDestroy()
    }

    private fun startForegroundService() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val importance = if (com.example.bluemesh.BuildConfig.HIGH_IMPORTANCE_NOTIFICATION)
            NotificationManager.IMPORTANCE_HIGH
        else
            NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BlueMesh BLE Service",
            importance
        ).apply {
            description = "Keeps Bluetooth connections alive in background"
            setShowBadge(com.example.bluemesh.BuildConfig.SHOW_NOTIFICATION_BADGE)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BlueMesh")
            .setContentText("Bluetooth mesh service running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }
}
