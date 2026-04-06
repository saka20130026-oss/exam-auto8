package com.exam.auto.exam1

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.exam.auto.CameraHelper
import com.exam.auto.ClaudeApi
import com.exam.auto.R
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class Exam1Activity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tvTitle: TextView
    private lateinit var tvPhase: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvCapCount: TextView
    private lateinit var tvAnsCount: TextView
    private lateinit var tvCurrentQ: TextView
    private lateinit var tvBigAnswer: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private lateinit var tts: TextToSpeech
    private lateinit var cameraHelper: CameraHelper
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var sessionToken = 0
    private var currentSession = 1 // 1교시, 2교시
    private var currentProb = 1
    private var capCount = 0
    private var ansCount = 0

    // 1교시: 1~120, 2교시: 121~200
    private val answers = Array(200) { "" }

    private val CAPTURE_INTERVAL_MS = 30_000L
    private val SESSION1_END = 120
    private val SESSION2_END = 200

    private var apiKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exam)

        tvTitle = findViewById(R.id.tvTitle)
        tvPhase = findViewById(R.id.tvPhase)
        tvSession = findViewById(R.id.tvSession)
        tvCapCount = findViewById(R.id.tvCapCount)
        tvAnsCount = findViewById(R.id.tvAnsCount)
        tvCurrentQ = findViewById(R.id.tvCurrentQ)
        tvBigAnswer = findViewById(R.id.tvBigAnswer)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        tvTitle.text = "📋 감정평가사 1차"
        tvTitle.setTextColor(0xFFffd700.toInt())

        apiKey = getSharedPreferences("exam_prefs", MODE_PRIVATE)
            .getString("claude_api_key", "") ?: ""

        tts = TextToSpeech(this, this)
        cameraHelper = CameraHelper(this, onStateChange = { msg -> log(msg) })

        btnStart.setOnClickListener { handleStart() }
        btnStop.setOnClickListener { handleStop() }

        log("감정평가사 1차 자동화 준비")
        log("1교시: 1~120번 | 2교시: 121~200번")
        log("API 키: ${if (apiKey.isNotEmpty()) "설정됨" else "⚠️ 미설정"}")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
            tts.setSpeechRate(1.0f)
        }
    }

    private fun speak(text: String) = tts.speak(text, TextToSpeech.QUEUE_ADD, null, null)

    private fun handleStart() {
        if (apiKey.isEmpty()) { log("❌ API 키 미설정"); return }
        running = true
        sessionToken++
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        currentSession = 1
        currentProb = 1
        tvSession.text = "1교시"
        tvPhase.text = "촬영중"
        speak("1교시 시작합니다.")
        log("1교시 시작")

        cameraHelper.start { ok ->
            runOnUiThread {
                if (ok) {
                    log("✅ USB 카메라 연결")
                    scheduleCapture(sessionToken)
                } else {
                    log("❌ 카메라 실패")
                    handleStop()
                }
            }
        }
    }

    private fun handleStop() {
        running = false
        sessionToken++
        handler.removeCallbacksAndMessages(null)
        cameraHelper.stop()
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        tvPhase.text = "정지"
        speak("정지합니다.")
        log("정지")
    }

    private fun scheduleCapture(token: Int) {
        handler.postDelayed({
            if (!running || sessionToken != token) return@postDelayed
            doCapture(token)
        }, CAPTURE_INTERVAL_MS)
    }

    private fun doCapture(token: Int) {
        if (!running || sessionToken != token) return
        executor.submit {
            cameraHelper.captureFrame { bytes ->
                if (bytes == null) {
                    runOnUiThread { scheduleCapture(token) }
                    return@captureFrame
                }
                runOnUiThread {
                    capCount++
                    tvCapCount.text = capCount.toString()
                    log("캡처 #$capCount (Q$currentProb)")
                }
                try {
                    val result = ClaudeApi.analyze(
                        apiKey = apiKey,
                        imageBytes = bytes,
                        systemPrompt = SYSTEM_PROMPT,
                        userPrompt = "현재 문제 번호는 ${currentProb}번부터 시작합니다."
                    )
                    runOnUiThread { parseResult(result, token) }
                } catch (e: Exception) {
                    runOnUiThread {
                        log("API 오류: ${e.message}")
                        scheduleCapture(token)
                    }
                }
            }
        }
    }

    private fun parseResult(json: String, token: Int) {
        try {
            val clean = json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(clean)
            if (obj.getString("status") == "retry") {
                log("재촬영 필요")
                scheduleCapture(token)
                return
            }
            val results = obj.getJSONArray("results")
            var lastQ = currentProb
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val q = item.getInt("probNum")
                val ans = item.getString("answer")
                if (q in 1..200) {
                    answers[q - 1] = ans
                    lastQ = q
                    ansCount++
                }
            }
            tvAnsCount.text = ansCount.toString()
            val lastAns = answers[lastQ - 1]
            tvBigAnswer.text = lastAns
            tvCurrentQ.text = "Q$lastQ"
            speak("${lastQ}번 ${lastAns}")
            log("Q${currentProb}~$lastQ 정답 수신")
            currentProb = lastQ + 1

            // 교시 전환 체크
            if (currentSession == 1 && currentProb > SESSION1_END) {
                currentSession = 2
                currentProb = SESSION1_END + 1
                tvSession.text = "2교시"
                speak("1교시 완료. 쉬는 시간입니다. 2교시를 시작합니다.")
                log("2교시 전환")
            }
            if (currentProb > SESSION2_END) {
                setPhase("done")
                speak("전체 완료. 수고하셨습니다.")
                log("✅ 완료")
                return
            }
            scheduleCapture(token)
        } catch (e: Exception) {
            log("파싱 오류: ${e.message}")
            scheduleCapture(token)
        }
    }

    private fun setPhase(p: String) {
        tvPhase.text = when(p) {
            "done" -> "완료"
            else -> p
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            val time = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
            tvLog.append("[$time] $msg\n")
            scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handleStop()
        tts.shutdown()
        executor.shutdown()
    }

    companion object {
        private const val SYSTEM_PROMPT = """당신은 감정평가사 1차 시험 객관식 문제 풀이 전문가입니다.
카메라로 촬영된 시험지 이미지를 분석합니다.
이미지에 보이는 모든 문제를 전부 풀고 JSON으로만 응답하세요.

{"status":"success","results":[{"probNum":1,"answer":"3"},{"probNum":2,"answer":"1"}],"message":"완료"}

문제가 안 보이면:
{"status":"retry","results":[],"message":"재촬영"}

규칙: answer는 1~5, JSON 외 텍스트 금지"""
    }
}
