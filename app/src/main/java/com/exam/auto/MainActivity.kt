package com.exam.auto

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.exam.auto.exam1.Exam1Activity
import com.exam.auto.exam2.Exam2Activity
import com.exam.auto.toeic.ToeicActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvCamStatus: TextView
    private lateinit var camStatusDot: View
    private lateinit var btnToeic: Button
    private lateinit var btnExam1: Button
    private lateinit var btnExam2: Button
    private lateinit var btnSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvCamStatus = findViewById(R.id.tvCamStatus)
        camStatusDot = findViewById(R.id.camStatusDot)
        btnToeic = findViewById(R.id.btnToeic)
        btnExam1 = findViewById(R.id.btnExam1)
        btnExam2 = findViewById(R.id.btnExam2)
        btnSettings = findViewById(R.id.btnSettings)

        checkUsbCamera()

        btnToeic.setOnClickListener {
            startActivity(Intent(this, ToeicActivity::class.java))
        }
        btnExam1.setOnClickListener {
            startActivity(Intent(this, Exam1Activity::class.java))
        }
        btnExam2.setOnClickListener {
            startActivity(Intent(this, Exam2Activity::class.java))
        }
        btnSettings.setOnClickListener {
            showApiKeyDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        checkUsbCamera()
    }

    private fun checkUsbCamera() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val devices = usbManager.deviceList
        if (devices.isNotEmpty()) {
            val device = devices.values.first()
            tvCamStatus.text = "USB 카메라 연결됨: ${device.deviceName}"
            tvCamStatus.setTextColor(0xFF39FF14.toInt())
            camStatusDot.setBackgroundColor(0xFF39FF14.toInt())
        } else {
            tvCamStatus.text = "USB 카메라 미연결"
            tvCamStatus.setTextColor(0xFF4a6a8a.toInt())
            camStatusDot.setBackgroundColor(0xFFff3366.toInt())
        }
    }

    private fun showApiKeyDialog() {
        val prefs = getSharedPreferences("exam_prefs", MODE_PRIVATE)
        val currentKey = prefs.getString("claude_api_key", "") ?: ""

        val input = EditText(this).apply {
            setText(currentKey)
            hint = "sk-ant-..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("Claude API 키 설정")
            .setMessage("Anthropic API 키를 입력하세요")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val key = input.text.toString().trim()
                prefs.edit().putString("claude_api_key", key).apply()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
