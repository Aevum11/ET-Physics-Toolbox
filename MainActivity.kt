package com.et.physics.toolbox

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var serviceBinder: OmniMeasureService? = null
    private var isBound = false
    private lateinit var prefs: SharedPreferences

    private lateinit var visualizer: VisualizerView
    private lateinit var spinnerMode: Spinner
    private lateinit var txtModeHelp: TextView
    private lateinit var txtValues: TextView
    private lateinit var txtStats: TextView
    private lateinit var txtBattery: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnCalibrate: Button
    private lateinit var btnReport: Button
    private lateinit var btnAbout: Button

    private val BUILD_DATE = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private val APP_VERSION = "9.0-Prod"

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() { updateUI(); handler.postDelayed(this, 100) }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as OmniMeasureService.LocalBinder
            serviceBinder = binder.getService()
            isBound = true
            if (serviceBinder?.currentMode != null) spinnerMode.setSelection(serviceBinder!!.currentMode.ordinal)
        }
        override fun onServiceDisconnected(arg0: ComponentName) { isBound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("ET_Prefs", Context.MODE_PRIVATE)

        visualizer = findViewById(R.id.visualizer)
        spinnerMode = findViewById(R.id.spinnerMode)
        txtModeHelp = findViewById(R.id.txtModeHelp)
        txtValues = findViewById(R.id.txtValues)
        txtStats = findViewById(R.id.txtStats)
        txtBattery = findViewById(R.id.txtBattery)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnReport = findViewById(R.id.btnReport)
        btnAbout = findViewById(R.id.btnAbout)

        setupSpinner()
        checkPermissions()
        setupNetworkMonitor()

        btnStart.setOnClickListener {
            val mode = OmniMeasureService.ScanMode.values()[spinnerMode.selectedItemPosition]
            val intent = Intent(this, OmniMeasureService::class.java)
            startService(intent)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
            serviceBinder?.startMeasurement(mode)
            handler.post(updateRunnable)
        }
        btnStop.setOnClickListener { serviceBinder?.stopMeasurement(); handler.removeCallbacks(updateRunnable) }
        btnCalibrate.setOnClickListener { showCalibrationDialog() }
        btnReport.setOnClickListener { checkPrivacyAndShowReport() }
        btnAbout.setOnClickListener { showAboutDialog() }
    }

    private fun setupSpinner() {
        val modes = OmniMeasureService.ScanMode.values().map { it.name.replace("_", " ") }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMode.adapter = adapter
        spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                txtModeHelp.text = when(position) {
                    0 -> "5Hz. Low power monitoring."; 1 -> "16Hz. Precise leveling. Mic off."
                    2 -> "50Hz. Motor check. Gyro+Mic."; 3 -> "16Hz. Environmental noise."; 4 -> "Light metering."
                    else -> ""
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateUI() {
        if (!isBound || serviceBinder == null) return
        val result = serviceBinder?.lastResult ?: return

        txtValues.text = "Rate: %.1f Hz | ISO: %s\nVel: %.2f mm/s | SPL: %.1f".format(
            result.realHz, result.isoZone, result.velocityRms, result.correctedSPL
        )
        txtStats.text = "Pk: %.0fHz %s | TTF: %.1fh\nLux: %.0f (%s)".format(
            result.dominantFreq, result.freqLabel, result.ttfPrediction, result.illuminanceLux, result.lightSource
        )

        visualizer.update(result.preciseTilt[0], result.preciseTilt[1], result.rmsVibration, result.correctedSPL, result.illuminanceLux)

        val drain = serviceBinder?.batteryDrainRate ?: 0.0
        val dur = serviceBinder?.getDurationString() ?: "00:00"
        val temp = serviceBinder?.batteryTemp ?: 0.0f
        txtBattery.text = "Session: $dur | %.1f°C | -%.2f%%/hr".format(temp, drain)
        if (temp > 45.0f) txtBattery.setTextColor(Color.RED) else txtBattery.setTextColor(if(drain>5.0) Color.YELLOW else Color.GREEN)
    }

    private fun checkPrivacyAndShowReport() {
        if (!prefs.getBoolean("privacy_opt_in", false)) showPrivacyConsentDialog() else showReportDialog()
    }

    private fun showPrivacyConsentDialog() {
        AlertDialog.Builder(this).setTitle("Privacy & Reporting").setMessage("Upload diagnostics? (Opt-In).\nNO Personal Data.")
            .setPositiveButton("Enable") { _, _ -> prefs.edit().putBoolean("privacy_opt_in", true).apply(); showReportDialog() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showReportDialog() {
        if (!isBound) return
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 10) }
        val inputIp = EditText(this).apply { hint = "Server URL (https://...)"; setText(prefs.getString("server_ip", "")) }
        val inputKey = EditText(this).apply { hint = "API Token"; setText(prefs.getString("api_key", "")) }
        val inputDesc = EditText(this).apply { hint = "Description..."; minLines = 3 }
        layout.addView(inputIp); layout.addView(inputKey); layout.addView(inputDesc)

        AlertDialog.Builder(this).setTitle("Send Report")
            .setView(layout)
            .setPositiveButton("Send") { _, _ ->
                prefs.edit().putString("server_ip", inputIp.text.toString()).putString("api_key", inputKey.text.toString()).apply()
                // Use Zipped Report
                val zipFile = serviceBinder?.getLogZip(this)
                if (zipFile != null) queueZipReport(zipFile, inputDesc.text.toString())
            }
            .setNeutralButton("Disable") { _, _ -> prefs.edit().putBoolean("privacy_opt_in", false).apply(); Toast.makeText(this,"Disabled",Toast.LENGTH_SHORT).show()}
            .setNegativeButton("Cancel", null).show()
    }
    
    // --- ZIP UPLOAD LOGIC ---
    
    private fun queueZipReport(zipFile: File, description: String) {
        val pendingDir = File(filesDir, "pending_reports")
        if (!pendingDir.exists()) pendingDir.mkdirs()
        val dest = File(pendingDir, zipFile.name)
        zipFile.copyTo(dest, overwrite = true)
        
        if (description.isNotEmpty()) {
            File(pendingDir, zipFile.name.replace(".zip", ".txt")).writeText(description)
        }
        Toast.makeText(this, "Queued Securely", Toast.LENGTH_SHORT).show()
        flushPendingReports()
    }

    private fun flushPendingReports() {
        val ip = prefs.getString("server_ip", "") ?: return
        if (ip.isEmpty()) return
        val apiKey = prefs.getString("api_key", "") ?: ""
        val pendingDir = File(filesDir, "pending_reports")
        val files = pendingDir.listFiles { _, name -> name.endsWith(".zip") } ?: return
        
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        if (net == null) return // No network

        thread {
            for (zipFile in files) {
                val descFile = File(pendingDir, zipFile.name.replace(".zip", ".txt"))
                val desc = if (descFile.exists()) descFile.readText() else ""
                
                var urlStr = ip
                if (!urlStr.startsWith("http")) urlStr = "https://$urlStr" // Default to HTTPS
                if (!urlStr.contains("/api/v1/upload")) urlStr += "/api/v1/upload"
                
                if (uploadSingleReport(zipFile, desc, urlStr, apiKey)) {
                    zipFile.delete(); if (descFile.exists()) descFile.delete()
                }
            }
        }
    }

    private fun uploadSingleReport(file: File, description: String, urlStr: String, apiKey: String): Boolean {
        try {
            val url = URL(urlStr)
            val boundary = "Boundary-${System.currentTimeMillis()}"
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if(apiKey.isNotEmpty()) conn.setRequestProperty("X-ET-AUTH-TOKEN", apiKey)
            
            val os = DataOutputStream(conn.outputStream)
            if (description.isNotEmpty()) os.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"description\"\r\n\r\n$description\r\n")
            os.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\nContent-Type: application/zip\r\n\r\n")
            file.inputStream().use { it.copyTo(os) }
            os.writeBytes("\r\n--$boundary--\r\n"); os.close()
            return conn.responseCode in 200..299
        } catch (e: Exception) { return false }
    }

    private fun setupNetworkMonitor() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { flushPendingReports() }
        })
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this).setTitle("ET Physics Toolbox").setMessage("v$APP_VERSION ($BUILD_DATE)\nEngine: OmniTraverser v9.0\nDevice: ${android.os.Build.MODEL}").setPositiveButton("OK", null).show()
    }
    
    private fun showCalibrationDialog() {
        AlertDialog.Builder(this).setTitle("Calibrate").setMessage("Keep flat for 5s.").setPositiveButton("OK") { _, _ -> serviceBinder?.runCalibration() }.show()
    }

    private fun checkPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.FOREGROUND_SERVICE, Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE), 1)
    }
}