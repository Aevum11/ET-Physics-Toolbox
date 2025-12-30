package com.et.omnimeasure

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.et.omnimeasure.core.OmniMeasureService
import com.et.omnimeasure.core.PhysicsUiModel
import com.et.omnimeasure.core.OmniTraverserEngine

class MainActivity : AppCompatActivity(), OmniTraverserEngine.TraverserListener {

    private var measureService: OmniMeasureService? = null
    private var serviceBinder: OmniMeasureService.LocalBinder? = null
    private var isBound = false
    
    private lateinit var txtTilt: TextView
    private lateinit var txtVib: TextView
    private lateinit var txtSound: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtMech: TextView
    private lateinit var btnMic: ToggleButton
    private lateinit var switchUltraEco: Switch

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            serviceBinder = service as OmniMeasureService.LocalBinder
            measureService = serviceBinder?.getService()
            measureService?.activeListener = this@MainActivity
            isBound = true
            btnMic.isChecked = false
        }
        override fun onServiceDisconnected(arg0: ComponentName) { isBound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        bindViews()
        checkPermissions()
        
        Toast.makeText(this, "Omni-Measure v12.0-Ind", Toast.LENGTH_SHORT).show()
    }

    private fun bindViews() {
        txtTilt = findViewById(R.id.txt_tilt)
        txtVib = findViewById(R.id.txt_vib)
        txtSound = findViewById(R.id.txt_sound)
        txtStatus = findViewById(R.id.txt_status_bar)
        txtMech = findViewById(R.id.txt_mech)
        
        btnMic = findViewById(R.id.btn_mic_toggle) 
        btnMic.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    serviceBinder?.setAudio(true)
                } else {
                    btnMic.isChecked = false
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                }
            } else {
                serviceBinder?.setAudio(false)
            }
        }
        
        switchUltraEco = findViewById(R.id.switch_ultra_eco)
        switchUltraEco.setOnCheckedChangeListener { _, isChecked ->
            serviceBinder?.setUltraEco(isChecked)
        }

        findViewById<Button>(R.id.btn_zero_tilt).setOnClickListener {
            serviceBinder?.calibrateTilt()
            Toast.makeText(this, "Tilt Zeroed", Toast.LENGTH_SHORT).show()
        }
        
        findViewById<Button>(R.id.btn_cal_db).setOnClickListener {
            serviceBinder?.calibrateDb(40.0)
            Toast.makeText(this, "Calibrated to 40dBA", Toast.LENGTH_SHORT).show()
        }
        
        // EXPORT LOG (SHARE INTENT)
        findViewById<Button>(R.id.btn_export).setOnClickListener {
            val csv = serviceBinder?.getLogCsv()
            if (!csv.isNullOrEmpty()) {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, csv)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "Omni-Measure Log")
                }
                startActivity(Intent.createChooser(sendIntent, "Export Log To..."))
            } else {
                Toast.makeText(this, "Log empty", Toast.LENGTH_SHORT).show()
            }
        }

        // RESET SESSION
        findViewById<Button>(R.id.btn_reset).setOnClickListener {
            serviceBinder?.resetSession()
            Toast.makeText(this, "Session Reset (Log Cleared)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startService() {
        val intent = Intent(this, OmniMeasureService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun checkPermissions() {
        val perms = arrayOf(Manifest.permission.FOREGROUND_SERVICE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE) != PackageManager.PERMISSION_GRANTED) {
             ActivityCompat.requestPermissions(this, perms, 102)
        } else {
            startService()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            btnMic.isChecked = true
            serviceBinder?.setAudio(true)
        } else if (requestCode == 102) {
            startService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            measureService?.activeListener = null
            unbindService(connection)
            isBound = false
        }
    }

    override fun onPhysicsUpdate(model: PhysicsUiModel) {
        runOnUiThread {
            txtTilt.text = "Level: %.2f° (±%.2f)".format(model.tiltDegrees, model.tiltConfidence)
            txtVib.text = "Vib: %.3f".format(model.vibrationRms)
            
            // Diagnostics
            val confPercent = (model.faultConfidence * 100).toInt()
            txtMech.text = "Src: %s\n%s (Conf: %d%%)".format(model.mechSource, model.faultPrediction, confPercent)
            
            // Status & Warnings
            if (model.statusMessage.isNotEmpty()) {
                txtStatus.text = model.statusMessage // e.g. "Warm Sensor"
                txtStatus.setTextColor(Color.RED)
            } else if (model.isUltraEco) {
                txtStatus.text = "ULTRA ECO"
                txtStatus.setTextColor(Color.GREEN)
            } else if (model.isEcoMode) {
                txtStatus.text = "ECO"
                txtStatus.setTextColor(Color.YELLOW)
            } else {
                txtStatus.text = "ACTIVE"
                txtStatus.setTextColor(Color.CYAN)
            }

            if (btnMic.isChecked) {
                txtSound.text = "Sound: %.1f dBA (±%.1f)".format(model.dbA, model.dbConfidence)
            } else {
                txtSound.text = "Sound: OFF"
            }
        }
    }

    override fun onPowerStateChange(isEcoMode: Boolean, isUltraEco: Boolean) {}
    override fun onError(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
}