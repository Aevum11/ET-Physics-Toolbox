package com.et.physics.toolbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentLinkedQueue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.*
import kotlin.math.sqrt

class OmniMeasureService : Service(), SensorEventListener {

    enum class ScanMode { BATTERY_SAVER, LEVEL_STABILITY, VIBRATION_MONITOR, NOISE_SCANNER, LIGHT_METER }

    inner class LocalBinder : Binder() { fun getService(): OmniMeasureService = this@OmniMeasureService }

    private val binder = LocalBinder()
    private lateinit var sensorManager: SensorManager
    private val etEngine = OmniTraverserEngine()
    private lateinit var prefs: SharedPreferences
    private lateinit var windowManager: WindowManager

    private val dataLog = ConcurrentLinkedQueue<String>()
    var lastResult: OmniTraverserEngine.SubstantiatedResult? = null
        private set

    var currentMode = ScanMode.LEVEL_STABILITY
    var sessionStartTime: Long = 0
    private var startBatteryLevel: Int = 0
    var batteryDrainRate: Double = 0.0
    var batteryTemp: Float = 0.0f
    
    private var isThrottled = false
    private val deviceKey = Build.MODEL.replace(" ", "_")

    private var isRunning = false
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioRecord: AudioRecord? = null
    private val sampleRate = 16000
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("ET_Prefs", Context.MODE_PRIVATE)
        loadCalibration()
        createNotificationChannel()
        val modeIdx = prefs.getInt("last_mode_$deviceKey", ScanMode.LEVEL_STABILITY.ordinal)
        currentMode = ScanMode.values().getOrElse(modeIdx) { ScanMode.LEVEL_STABILITY }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") { stopMeasurement(); stopForeground(true); stopSelf() }
        return START_NOT_STICKY
    }

    fun startMeasurement(mode: ScanMode) {
        if (isRunning) stopMeasurement()
        isRunning = true; currentMode = mode
        prefs.edit().putInt("last_mode_$deviceKey", mode.ordinal).apply()
        sessionStartTime = System.currentTimeMillis()
        startBatteryLevel = getBatteryStats().level
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ET:ScanLock")
        wakeLock?.acquire(12*60*60*1000L)
        startForeground(1, buildNotification("ET Active: $mode", "v9.0-Hardened"))
        registerSensorsForMode(mode)
        if (mode == ScanMode.NOISE_SCANNER || mode == ScanMode.VIBRATION_MONITOR) startAudioLoop()
    }
    
    private fun registerSensorsForMode(mode: ScanMode) {
        val delay = when(mode) {
            ScanMode.BATTERY_SAVER, ScanMode.LIGHT_METER -> SensorManager.SENSOR_DELAY_NORMAL
            ScanMode.LEVEL_STABILITY, ScanMode.NOISE_SCANNER -> SensorManager.SENSOR_DELAY_UI
            else -> SensorManager.SENSOR_DELAY_GAME
        }
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, accel, delay)
        if (mode == ScanMode.VIBRATION_MONITOR) {
            val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            sensorManager.registerListener(this, gyro, delay)
        }
        val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (light != null && (mode == ScanMode.LIGHT_METER || mode == ScanMode.LEVEL_STABILITY)) {
            sensorManager.registerListener(this, light, delay)
        }
    }

    fun stopMeasurement() {
        isRunning = false; job?.cancel(); sensorManager.unregisterListener(this); stopAudio(); wakeLock?.release()
    }

    private fun startAudioLoop() {
        job = scope.launch {
            try {
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                val buffer = ShortArray(bufferSize)
                while (isActive && isRunning && !isThrottled) {
                    if (currentMode == ScanMode.NOISE_SCANNER) {
                        audioRecord?.startRecording(); val endTime = System.currentTimeMillis() + 5000 
                        while (System.currentTimeMillis() < endTime && isActive) {
                            val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                            if (read > 0) synchronized(this) { currentAudioBuffer = buffer.clone() }
                        }
                        audioRecord?.stop(); delay(55000)
                    } else if (currentMode == ScanMode.VIBRATION_MONITOR) {
                         if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) audioRecord?.startRecording()
                         val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                         if (read > 0) synchronized(this) { currentAudioBuffer = buffer.clone() }
                         delay(50)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
    private fun stopAudio() { try { audioRecord?.stop(); audioRecord?.release() } catch(e: Exception) {} }

    private var lastAccel = FloatArray(3); private var lastGyro = FloatArray(3); private var currentLux: Float? = null; private var currentAudioBuffer: ShortArray? = null

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) lastAccel = event.values.clone()
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) lastGyro = event.values.clone()
        if (event.sensor.type == Sensor.TYPE_LIGHT) currentLux = event.values[0]
        if (isThrottled && System.currentTimeMillis() % 10 != 0L) return

        val bufferToProcess = synchronized(this) { val b = currentAudioBuffer; currentAudioBuffer = null; b }
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) try { baseContext.display?.rotation ?: 0 } catch(e:Exception) {0} else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        
        val result = etEngine.resolveConfiguration(lastAccel, lastGyro, bufferToProcess, currentLux, rotation, System.nanoTime())
        lastResult = result
        logData(result, System.currentTimeMillis())
        updateBatteryStats(System.currentTimeMillis())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun logData(result: OmniTraverserEngine.SubstantiatedResult, time: Long) {
        val timeString = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(time))
        // Sanitize: No Device ID, Serial, Location. Pure Physics Data.
        val line = "$timeString,${result.realHz},${result.shimmerIndex},${result.velocityRms},${result.isoZone},${result.correctedSPL},${result.illuminanceLux},${result.dominantFreq},${result.freqLabel},${result.faultSeverity}"
        dataLog.add(line)
        if (dataLog.size > 10000) dataLog.poll()
    }
    
    // --- ZIP EXPORT ---
    fun getLogZip(context: Context): File {
        val timestamp = System.currentTimeMillis()
        val zipFile = File(context.cacheDir, "et_report_$timestamp.zip")
        
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val entry = ZipEntry("session_data.csv")
            zos.putNextEntry(entry)
            val header = "Time,Hz,Shimmer,Vel_mm_s,ISO_Zone,SPL_dBA,Lux,Freq_Hz,Label,Severity\n"
            zos.write(header.toByteArray())
            dataLog.forEach { line -> zos.write((line + "\n").toByteArray()) }
            zos.closeEntry()
        }
        return zipFile
    }

    private data class BatteryStats(val level: Int, val temp: Float)
    private fun getBatteryStats(): BatteryStats {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 100
        val tempRaw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return BatteryStats(level, tempRaw / 10.0f)
    }

    private fun updateBatteryStats(now: Long) {
        if ((now - sessionStartTime) % 60000 < 100) {
            val stats = getBatteryStats()
            batteryTemp = stats.temp
            if (batteryTemp > 45.0f && !isThrottled) { isThrottled = true; registerSensorsForMode(ScanMode.BATTERY_SAVER) }
            else if (batteryTemp < 40.0f && isThrottled) { isThrottled = false; registerSensorsForMode(currentMode) }
            val hrs = (now - sessionStartTime) / 3600000.0
            if (hrs > 0.1) batteryDrainRate = (startBatteryLevel - stats.level) / hrs
        }
    }
    
    fun runCalibration() {
        scope.launch {
            val samples = 50; var ax = 0.0; var ay = 0.0; var az = 0.0
            for(i in 1..samples) { ax += lastAccel[0]; ay += lastAccel[1]; az += lastAccel[2]; delay(20) }
            ax /= samples; ay /= samples; az /= samples
            prefs.edit().putFloat("cal_acc_x_$deviceKey", ax.toFloat()).putFloat("cal_acc_y_$deviceKey", ay.toFloat()).putFloat("cal_acc_z_$deviceKey", az.toFloat()).apply()
            etEngine.setCalibration(floatArrayOf(ax.toFloat(), ay.toFloat(), az.toFloat()), floatArrayOf(0f,0f,0f), 0.0)
        }
    }

    private fun loadCalibration() {
        val ax = prefs.getFloat("cal_acc_x_$deviceKey", 0f); val ay = prefs.getFloat("cal_acc_y_$deviceKey", 0f); val az = prefs.getFloat("cal_acc_z_$deviceKey", 0f)
        val spl = prefs.getFloat("cal_spl_$deviceKey", 0f).toDouble()
        etEngine.setCalibration(floatArrayOf(ax, ay, az), floatArrayOf(0f,0f,0f), spl)
    }

    fun getDurationString(): String {
        if (sessionStartTime == 0L) return "00:00"
        val diff = System.currentTimeMillis() - sessionStartTime
        return String.format("%02d:%02d", diff/3600000, (diff/60000)%60)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, "ET_CHANNEL_ID")
            .setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.ic_menu_compass).setContentIntent(pendingIntent)
        if (isThrottled) builder.setSubText("⚠ COOLING MODE")
        return builder.build()
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("ET_CHANNEL_ID", "ET Scanner", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}