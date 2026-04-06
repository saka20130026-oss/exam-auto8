package com.exam.auto.exam2

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

class Exam2Activity : AppCompatActivity(), TextToSpeech.OnInitListener {

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
    private var capCount = 0
    private var ansCount = 0
    private var currentSession = 1

    // 2차는 주관식 — 모범답안 낭독
    private val answerTexts = mutableListOf<String>()
    private var currentDictIndex = 0

    private val CAPTURE_INTERVAL_MS = 45_000L
    private val DICTATION_DELAY_MS = 5_000L

    private var apiKey = ""
    private var ttsReady = false

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

        tvTitle.text = "📝 감정평가사 2차"
        tvTitle.setTextColor(0xFFc084fc.toInt())
        btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF550055.toInt())

        apiKey = getSharedPreferences("exam_prefs", MODE_PRIVATE)
            .getString("claude_api_key", "") ?: ""

        tts = TextToSpeech(this, this)
        cameraHelper = CameraHelper(this, onStateChange = { msg -> log(msg) })

        btnStart.setOnClickListener { handleStart() }
        btnStop.setOnClickListener { handleStop() }

        log("감정평가사 2차 자동화 준비")
        log("촬영 → Claude 모범답안 분석 → 느리게 낭독")
        log("API 키: ${if (apiKey.isNotEmpty()) "설정됨" else "⚠️ 미설정"}")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
            tts.setSpeechRate(0.75f) // ★ 느리게
            tts.setPitch(1.0f)
            ttsReady = true

            // TTS 완료 콜백 — 낭독 완료 후 다음 항목
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "DICTATION") {
                        runOnUiThread { nextDictation() }
                    }
                }
                override fun onError(utteranceId: String?) {}
            })
            log("TTS 준비 완료 (속도: 0.75x 느리게)")
        }
    }

    private fun speak(text: String, utteranceId: String = "") {
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    private fun handleStart() {
        if (apiKey.isEmpty()) { log("❌ API 키 미설정"); return }
        running = true
        sessionToken++
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        currentSession = 1
        tvSession.text = "1교시"
        tvPhase.text = "촬영중"
        speak("2차 시험 자동화를 시작합니다.")
        log("2차 시작")

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
        tts.stop()
        cameraHelper.stop()
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        tvPhase.text = "정지"
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
                    tvPhase.text = "분석중"
                    log("캡처 #$capCount — Claude 분석 중...")
                }
                try {
                    val result = ClaudeApi.analyze(
                        apiKey = apiKey,
                        imageBytes = bytes,
                        systemPrompt = SYSTEM_PROMPT_2ND,
                        userPrompt = "이미지에 보이는 문제의 모범 답안을 작성해주세요. 낭독하기 좋게 자연스러운 문장으로 작성하세요."
                    )
                    runOnUiThread { handleAnswer(result, token) }
                } catch (e: Exception) {
                    runOnUiThread {
                        log("API 오류: ${e.message}")
                        tvPhase.text = "촬영중"
                        scheduleCapture(token)
                    }
                }
            }
        }
    }

    private fun handleAnswer(result: String, token: Int) {
        try {
            val clean = result.trim().removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val obj = JSONObject(clean)

            if (obj.getString("status") == "retry") {
                log("재촬영 필요")
                tvPhase.text = "촬영중"
                scheduleCapture(token)
                return
            }

            val qNum = obj.optString("questionNum", "?")
            val answer = obj.getString("answer")
            answerTexts.add(answer)
            ansCount++
            tvAnsCount.text = ansCount.toString()
            tvCurrentQ.text = "Q$qNum"
            tvBigAnswer.text = "낭독중"
            tvPhase.text = "낭독중"
            log("Q$qNum 모범답안 수신 — 낭독 시작")

            // 낭독
            handler.postDelayed({
                if (!running || sessionToken != token) return@postDelayed
                startDictation(answer, token)
            }, DICTATION_DELAY_MS)

        } catch (e: Exception) {
            log("파싱 오류: ${e.message}")
            // JSON 파싱 실패시 그냥 텍스트로 낭독
            answerTexts.add(result)
            startDictation(result, token)
        }
    }

    private fun startDictation(text: String, token: Int) {
        if (!running || sessionToken != token) return
        speak(text, "DICTATION")
    }

    private fun nextDictation() {
        if (!running) return
        val token = sessionToken
        tvPhase.text = "촬영중"
        tvBigAnswer.text = "✅"
        log("낭독 완료 — 다음 촬영 대기")
        scheduleCapture(token)
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
        private const val SYSTEM_PROMPT_2ND = """당신은 감정평가사 2차 시험 전문가입니다.
카메라로 촬영된 시험지를 보고 모범 답안을 작성합니다.
반드시 아래 JSON 형식으로만 응답하세요.

문제가 잘 보이는 경우:
{"status":"success","questionNum":"1","answer":"감정평가란 토지 등의 경제적 가치를 판정하여 그 결과를 가액으로 표시하는 것을 말한다. 감정평가사는..."}

문제가 안 보이는 경우:
{"status":"retry","questionNum":"","answer":""}

규칙:
- answer는 TTS로 낭독하기 좋은 자연스러운 문장
- 핵심 키워드 반드시 포함
- JSON 외 텍스트 절대 금지"""
    }
}
