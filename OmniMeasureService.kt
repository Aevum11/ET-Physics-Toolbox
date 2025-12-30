package com.et.omnimeasure.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.et.omnimeasure.MainActivity
import com.et.omnimeasure.R
import java.util.ArrayDeque
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OmniMeasureService : Service(), OmniTraverserEngine.TraverserListener {

    private val binder = LocalBinder()
    var engine: OmniTraverserEngine? = null
    var activeListener: OmniTraverserEngine.TraverserListener? = null
    
    private val CHANNEL_ID = "OmniMeasureChannel"
    private val NOTIFICATION_ID = 1
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationUpdate: Long = 0
    private val NOTIF_UPDATE_INTERVAL = 60_000L
    
    private val APP_VERSION = "v12.0-Ind"
    
    // --- LOGGING ---
    private val logBuffer = ArrayDeque<String>(2000) 
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var lastLogTime = 0L

    inner class LocalBinder : Binder() {
        fun getService(): OmniMeasureService = this@OmniMeasureService
        
        fun calibrateTilt() = engine?.calibrateTiltZero()
        fun calibrateDb(ref: Double) = engine?.calibrateDb(ref)
        fun setAudio(enable: Boolean) = engine?.setAudioEnabled(enable)
        fun setUltraEco(enable: Boolean) = engine?.setUltraEco(enable)
        
        fun resetSession() {
            synchronized(logBuffer) { logBuffer.clear() }
            engine?.calibrateTiltZero()
            engine?.engage() // Resets session timer in engine
        }
        
        fun getLogCsv(): String {
            val sb = StringBuilder()
            sb.append("Omni-Measure Session Log\n")
            sb.append("Version: $APP_VERSION\n")
            sb.append("Export Date: ${dateFormat.format(Date())}\n\n")
            sb.append("Time,Tilt(deg),VibRMS(m/s^2),MechFreq(Hz),dBA,FaultPred,Confidence,Severity\n")
            synchronized(logBuffer) {
                for (line in logBuffer) sb.append(line).append("\n")
            }
            return sb.toString()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmniMeasure:TraverserLock").apply {
            setReferenceCounted(false)
        }
        
        engine = OmniTraverserEngine(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        wakeLock?.acquire(24 * 60 * 60 * 1000L)

        val notification = buildNotification("Omni-Measure Active", "Observer Ready ($APP_VERSION)")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
             startForeground(NOTIFICATION_ID, notification, 
                 if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) 
                     ServiceInfo.FOREGROUND_SERVICE_TYPE_SENSOR or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                 else 
                     ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
             )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        engine?.engage()
        
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.disengage()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onPhysicsUpdate(model: PhysicsUiModel) {
        activeListener?.onPhysicsUpdate(model)
        
        val severity = calculateSeverity(model)

        val now = System.currentTimeMillis()
        if (now - lastLogTime > 500) {
            lastLogTime = now
            val logEntry = "%s,%.2f,%.3f,%.1f,%.1f,%s,%.2f,%s".format(
                dateFormat.format(Date()), 
                model.tiltDegrees, 
                model.vibrationRms, 
                model.mechFreq, 
                model.dbA,
                model.faultPrediction,
                model.faultConfidence,
                severity
            )
            synchronized(logBuffer) {
                if (logBuffer.size >= 2000) logBuffer.removeFirst()
                logBuffer.addLast(logEntry)
            }
        }
        
        if (now - lastNotificationUpdate > NOTIF_UPDATE_INTERVAL) {
            lastNotificationUpdate = now
            updateNotification(model, severity)
        }
    }

    override fun onError(message: String) {
        activeListener?.onError(message)
    }
    
    override fun onPowerStateChange(isEcoMode: Boolean, isUltraEco: Boolean) {
        activeListener?.onPowerStateChange(isEcoMode, isUltraEco)
    }
    
    private fun calculateSeverity(model: PhysicsUiModel): String {
        return when {
            model.faultConfidence > 0.8 -> "HIGH"
            model.faultConfidence > 0.5 -> "MEDIUM"
            model.faultConfidence > 0.2 -> "LOW"
            else -> "NONE"
        }
    }
    
    private fun updateNotification(model: PhysicsUiModel, severity: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val ecoText = if (model.isUltraEco) "ULTRA ECO" else if (model.isEcoMode) "ECO" else "ACTIVE"
        val severityText = if (severity != "NONE") "| Sev: $severity" else ""
        
        val content = "Level: %.1f° | Vib: %.3f | $ecoText $severityText".format(
            model.tiltDegrees, 
            model.vibrationRms
        )
        
        val notification = buildNotification("Omni-Measure $APP_VERSION", content)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Omni-Measure Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}