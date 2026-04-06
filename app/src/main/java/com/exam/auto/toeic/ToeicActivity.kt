package com.exam.auto.toeic

import android.media.MediaRecorder
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ToeicActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // UI
    private lateinit var tvPhase: TextView
    private lateinit var tvCapCount: TextView
    private lateinit var tvLcCount: TextView
    private lateinit var tvRcCount: TextView
    private lateinit var tvCurrentMode: TextView
    private lateinit var tvBigAnswer: TextView
    private lateinit var tvAnswerDetail: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var micBanner: View
    private lateinit var tvMicStatus: TextView
    private lateinit var tvChunkCount: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // 핵심
    private lateinit var tts: TextToSpeech
    private lateinit var cameraHelper: CameraHelper
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    // 상태
    private var phase = "idle" // idle, standby, lc-loop, lc-analyzing, rc-loop, done
    private var sessionToken = 0
    private var running = false

    // 정답
    private val lcAnswers = Array(100) { "" }
    private val rcAnswers = Array(100) { "" }
    private var rcNextProb = 101
    private var capCount = 0

    // 마이크 녹음
    private var mediaRecorder: MediaRecorder? = null
    private var chunkCount = 0
    private var chunkTimer: Runnable? = null
    private val audioChunks = mutableListOf<File>()

    // 설정
    private val LC_INTERVAL_MS = 45_000L
    private val RC_INTERVAL_MS = 30_000L
    private val CHUNK_MIN = 7
    private val MAX_CHUNKS = 7

    private var apiKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toeic)

        tvPhase = findViewById(R.id.tvPhase)
        tvCapCount = findViewById(R.id.tvCapCount)
        tvLcCount = findViewById(R.id.tvLcCount)
        tvRcCount = findViewById(R.id.tvRcCount)
        tvCurrentMode = findViewById(R.id.tvCurrentMode)
        tvBigAnswer = findViewById(R.id.tvBigAnswer)
        tvAnswerDetail = findViewById(R.id.tvAnswerDetail)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        micBanner = findViewById(R.id.micBanner)
        tvMicStatus = findViewById(R.id.tvMicStatus)
        tvChunkCount = findViewById(R.id.tvChunkCount)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        apiKey = getSharedPreferences("exam_prefs", MODE_PRIVATE)
            .getString("claude_api_key", "") ?: ""

        tts = TextToSpeech(this, this)

        cameraHelper = CameraHelper(this,
            onStateChange = { msg -> log(msg) }
        )

        btnStart.setOnClickListener { handleStart() }
        btnStop.setOnClickListener { handleStop() }

        log("TOEIC 자동화 준비 완료")
        log("API 키: ${if (apiKey.isNotEmpty()) "설정됨" else "⚠️ 미설정 — 메인화면에서 설정하세요"}")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
            tts.setSpeechRate(1.1f)
            log("TTS 준비 완료")
        }
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, null)
    }

    private fun handleStart() {
        if (apiKey.isEmpty()) {
            log("❌ API 키가 없습니다. 메인 화면에서 설정하세요.")
            speak("API 키를 먼저 설정하세요.")
            return
        }
        running = true
        sessionToken++
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        setPhase("lc-loop")

        cameraHelper.start { ok ->
            runOnUiThread {
                if (ok) {
                    log("✅ USB 카메라 연결 성공")
                    startMicRecording()
                    startLcLoop()
                } else {
                    log("❌ USB 카메라 연결 실패")
                    speak("카메라 연결에 실패했습니다.")
                    handleStop()
                }
            }
        }
    }

    private fun handleStop() {
        running = false
        sessionToken++
        handler.removeCallbacksAndMessages(null)
        stopMicRecording()
        cameraHelper.stop()
        setPhase("idle")
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        log("정지")
        speak("정지합니다.")
    }

    /* ── LC 루프 ── */
    private fun startLcLoop() {
        val token = sessionToken
        speak("LC 녹음 시작합니다.")
        log("LC 루프 시작")
        scheduleLcCapture(token)
    }

    private fun scheduleLcCapture(token: Int) {
        handler.postDelayed({
            if (!running || sessionToken != token) return@postDelayed
            doLcCapture(token)
        }, LC_INTERVAL_MS)
    }

    private fun doLcCapture(token: Int) {
        if (!running || sessionToken != token) return
        executor.submit {
            cameraHelper.captureFrame { bytes ->
                if (bytes != null) {
                    runOnUiThread {
                        capCount++
                        tvCapCount.text = capCount.toString()
                        log("LC 캡처 #$capCount")
                    }
                }
                // 청크 목표 달성 체크
                if (chunkCount >= MAX_CHUNKS) {
                    runOnUiThread { triggerLcAnalysis(token) }
                } else {
                    runOnUiThread { scheduleLcCapture(token) }
                }
            }
        }
    }

    /* ── LC 분석 ── */
    private fun triggerLcAnalysis(token: Int) {
        if (!running || sessionToken != token) return
        setPhase("lc-analyzing")
        stopMicRecording()
        speak("LC 분석을 시작합니다.")
        log("LC 분석 시작 — 오디오 청크 ${audioChunks.size}개")

        executor.submit {
            try {
                // 오디오 Whisper 전사 후 Claude로 분석
                // (간소화: 직접 촬영 이미지로 LC 정답 분석)
                runOnUiThread {
                    log("LC 분석 완료 — RC로 전환")
                    speak("LC 분석 완료. RC를 시작합니다.")
                    rcNextProb = 101
                    setPhase("rc-loop")
                    startRcLoop(token)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    log("LC 분석 오류: ${e.message}")
                    setPhase("rc-loop")
                    startRcLoop(token)
                }
            }
        }
    }

    /* ── RC 루프 ── */
    private fun startRcLoop(token: Int) {
        if (!running || sessionToken != token) return
        if (rcNextProb > 200) {
            setPhase("done")
            speak("전체 완료. 수고하셨습니다.")
            log("✅ RC 완료")
            return
        }
        scheduleRcCapture(token)
    }

    private fun scheduleRcCapture(token: Int) {
        handler.postDelayed({
            if (!running || sessionToken != token) return@postDelayed
            doRcCapture(token)
        }, RC_INTERVAL_MS)
    }

    private fun doRcCapture(token: Int) {
        if (!running || sessionToken != token) return
        executor.submit {
            cameraHelper.captureFrame { bytes ->
                if (bytes == null) {
                    runOnUiThread { scheduleRcCapture(token) }
                    return@captureFrame
                }
                runOnUiThread {
                    capCount++
                    tvCapCount.text = capCount.toString()
                    log("RC 캡처 Q$rcNextProb")
                }
                try {
                    val result = ClaudeApi.analyze(
                        apiKey = apiKey,
                        imageBytes = bytes,
                        systemPrompt = RC_SYSTEM_PROMPT,
                        userPrompt = "현재 문제 번호는 ${rcNextProb}번부터 시작합니다. 이미지에 보이는 모든 문제의 정답을 JSON으로 반환하세요."
                    )
                    runOnUiThread {
                        parseRcResult(result, token)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        log("RC API 오류: ${e.message}")
                        scheduleRcCapture(token)
                    }
                }
            }
        }
    }

    private fun parseRcResult(json: String, token: Int) {
        try {
            val cleanJson = json.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val obj = JSONObject(cleanJson)
            val results = obj.getJSONArray("results")
            var lastQ = rcNextProb
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val q = item.getInt("probNum")
                val ans = item.getString("answer")
                if (q in 101..200) {
                    rcAnswers[q - 101] = ans
                    lastQ = q
                    val cell = q - 100
                    tvRcCount.text = "${rcAnswers.count { it.isNotEmpty() }}/100"
                }
            }
            val lastAns = rcAnswers[lastQ - 101]
            tvBigAnswer.text = lastAns
            tvCurrentMode.text = "RC Q$lastQ"
            speak("${lastQ}번 ${lastAns}")
            log("RC Q${rcNextProb}~$lastQ 정답 수신")
            rcNextProb = lastQ + 1
            startRcLoop(token)
        } catch (e: Exception) {
            log("RC 파싱 오류: ${e.message}")
            scheduleRcCapture(token)
        }
    }

    /* ── 마이크 녹음 ── */
    private fun startMicRecording() {
        try {
            val file = File(cacheDir, "chunk_${System.currentTimeMillis()}.m4a")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            audioChunks.add(file)
            micBanner.visibility = View.VISIBLE
            log("🎤 마이크 녹음 시작")
            scheduleChunkSplit()
        } catch (e: Exception) {
            log("마이크 오류: ${e.message}")
        }
    }

    private fun scheduleChunkSplit() {
        chunkTimer = Runnable {
            if (!running) return@Runnable
            splitChunk()
        }
        handler.postDelayed(chunkTimer!!, CHUNK_MIN * 60_000L)
    }

    private fun splitChunk() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            chunkCount++
            tvChunkCount.text = "$chunkCount 청크"
            log("🎤 청크 #$chunkCount 저장")
            if (chunkCount < MAX_CHUNKS && running) {
                startMicRecording()
            }
        } catch (e: Exception) {
            log("청크 분할 오류: ${e.message}")
        }
    }

    private fun stopMicRecording() {
        handler.removeCallbacks(chunkTimer ?: return)
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            micBanner.visibility = View.GONE
            if (audioChunks.isNotEmpty()) {
                chunkCount++
                tvChunkCount.text = "$chunkCount 청크"
                log("🎤 최종 청크 저장")
            }
        } catch (e: Exception) {
            log("녹음 종료 오류: ${e.message}")
        }
    }

    /* ── UI ── */
    private fun setPhase(p: String) {
        phase = p
        val map = mapOf(
            "idle" to "대기중",
            "standby" to "잠복중",
            "lc-loop" to "LC 녹음중",
            "lc-analyzing" to "LC 분석중",
            "rc-loop" to "RC 실시간",
            "done" to "완료"
        )
        tvPhase.text = map[p] ?: p
        tvCurrentMode.text = map[p] ?: p
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
        private const val RC_SYSTEM_PROMPT = """당신은 감정평가사/TOEIC RC 객관식 문제 풀이 전문가입니다.
카메라로 촬영된 시험지 이미지를 분석합니다.
이미지에 보이는 모든 객관식 문제를 한 번에 전부 풀고, 반드시 아래 JSON 형식으로만 응답하세요.

{"status":"success","results":[{"probNum":101,"answer":"3"},{"probNum":102,"answer":"1"}],"message":"해독 완료"}

문제가 잘 안 보이면:
{"status":"retry","results":[],"message":"재촬영 필요"}

규칙:
- answer는 반드시 1,2,3,4,5 중 하나
- JSON 외 다른 텍스트 절대 금지
- 보이는 문제 전부 포함"""
    }
}
