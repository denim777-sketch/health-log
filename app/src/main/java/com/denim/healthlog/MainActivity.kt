package com.denim.healthlog

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val sleepPerm = HealthPermission.getReadPermission(SleepSessionRecord::class)
    private val exercisePerm = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    private val distancePerm = HealthPermission.getReadPermission(DistanceRecord::class)
    private val allPerms = setOf(sleepPerm, exercisePerm, distancePerm)

    private val zone: ZoneId = ZoneId.systemDefault()
    private val timeFmt = DateTimeFormatter.ofPattern("H:mm")
    private val dayFmt = DateTimeFormatter.ofPattern("M月d日（E）", Locale.JAPANESE)

    private lateinit var list: LinearLayout
    private var client: HealthConnectClient? = null

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            loadData()
        }

    // ---- 色（ライト／ダーク） ----
    private val isDark get() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    private val cardColor get() = if (isDark) Color.parseColor("#22262B") else Color.parseColor("#F2F4F6")
    private val subColor get() = if (isDark) Color.parseColor("#9AA3AD") else Color.parseColor("#66707A")
    private val mainColor get() = if (isDark) Color.parseColor("#ECEFF2") else Color.parseColor("#1D2329")
    private val sleepColor = Color.parseColor("#6C7FD8")
    private val runColor = Color.parseColor("#E07A3F")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(4))
        }
        header.addView(TextView(this).apply {
            text = "睡眠と運動"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(mainColor)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "更新"
            setOnClickListener { loadData() }
        })
        root.addView(header)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(24))
        }
        root.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 画面端（ステータスバー・ナビゲーションバー）に重ならないようにする
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        start()
    }

    private fun start() {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                client = HealthConnectClient.getOrCreate(this)
                loadData()
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                showMessage("ヘルスコネクトの更新が必要です。", "Playストアを開く") {
                    openStore()
                }
            }
            else -> showMessage("この端末ではヘルスコネクトを利用できません。")
        }
    }

    private fun loadData() {
        val hc = client ?: return
        lifecycleScope.launch {
            try {
                val granted = hc.permissionController.getGrantedPermissions()
                if (!granted.contains(sleepPerm) || !granted.contains(exercisePerm)) {
                    showMessage(
                        "睡眠と運動の記録を読むには、ヘルスコネクトでの許可が必要です。",
                        "許可する"
                    ) { requestPermissions.launch(allPerms) }
                    return@launch
                }
                showMessage("読み込み中…")

                val end = Instant.now()
                val start = LocalDate.now(zone).minusDays(30).atStartOfDay(zone).toInstant()
                val range = TimeRangeFilter.between(start, end)

                val sleeps = readAll(hc, SleepSessionRecord::class, range)
                val exercises = readAll(hc, ExerciseSessionRecord::class, range)

                val distances = mutableMapOf<String, Double>()
                if (granted.contains(distancePerm)) {
                    for (ex in exercises) {
                        try {
                            val res = hc.aggregate(
                                AggregateRequest(
                                    metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                                    timeRangeFilter = TimeRangeFilter.between(ex.startTime, ex.endTime)
                                )
                            )
                            val km = res[DistanceRecord.DISTANCE_TOTAL]?.inKilometers
                            if (km != null && km > 0.05) distances[ex.metadata.id] = km
                        } catch (e: Exception) { }
                    }
                }
                render(sleeps, exercises, distances)
            } catch (e: Exception) {
                showMessage("読み込みに失敗しました：${e.message}", "もう一度") { loadData() }
            }
        }
    }

    private suspend fun <T : androidx.health.connect.client.records.Record> readAll(
        hc: HealthConnectClient,
        type: kotlin.reflect.KClass<T>,
        range: TimeRangeFilter
    ): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val resp = hc.readRecords(
                ReadRecordsRequest(recordType = type, timeRangeFilter = range, pageToken = token)
            )
            out.addAll(resp.records)
            token = resp.pageToken
        } while (token != null)
        return out
    }

    // ---- 表示 ----

    private fun render(
        sleeps: List<SleepSessionRecord>,
        exercises: List<ExerciseSessionRecord>,
        distances: Map<String, Double>
    ) {
        list.removeAllViews()

        // 睡眠は「起きた日」、運動は「始めた日」でまとめる
        val sleepByDay = sleeps.groupBy { it.endTime.atZone(zone).toLocalDate() }
        val exByDay = exercises.groupBy { it.startTime.atZone(zone).toLocalDate() }
        val days = (sleepByDay.keys + exByDay.keys).sortedDescending()

        list.addView(summaryCard(sleeps, exercises, distances))

        if (days.isEmpty()) {
            list.addView(note(
                "直近30日の記録が見つかりませんでした。\n\n" +
                    "Google Healthアプリ側で、ヘルスコネクトへの共有（睡眠・エクササイズ）がオンになっているか確認してください。"
            ))
            return
        }

        for (day in days) {
            val card = card()
            card.addView(TextView(this).apply {
                text = day.format(dayFmt)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(mainColor)
            })
            sleepByDay[day].orEmpty().sortedBy { it.startTime }.forEach { s ->
                card.addView(sleepRow(s))
            }
            exByDay[day].orEmpty().sortedBy { it.startTime }.forEach { e ->
                card.addView(exerciseRow(e, distances[e.metadata.id]))
            }
            list.addView(card)
        }
    }

    private fun summaryCard(
        sleeps: List<SleepSessionRecord>,
        exercises: List<ExerciseSessionRecord>,
        distances: Map<String, Double>
    ): View {
        val weekAgo = Instant.now().minus(7, ChronoUnit.DAYS)
        val recentSleeps = sleeps.filter { it.endTime.isAfter(weekAgo) }
        val sleepNights = recentSleeps.groupBy { it.endTime.atZone(zone).toLocalDate() }
        val avgSleepMin = if (sleepNights.isEmpty()) 0L else
            sleepNights.values.sumOf { nights -> nights.sumOf { asleepMinutes(it) } } / sleepNights.size

        val recentRuns = exercises.filter { it.startTime.isAfter(weekAgo) && isRun(it.exerciseType) }
        val runKm = recentRuns.sumOf { distances[it.metadata.id] ?: 0.0 }

        val card = card()
        card.addView(label("直近7日"))
        card.addView(TextView(this).apply {
            text = "平均睡眠  " + (if (avgSleepMin > 0) fmtMinutes(avgSleepMin) else "—") +
                "\nランニング  ${recentRuns.size}回" +
                (if (runKm > 0) String.format(Locale.JAPAN, "・%.1f km", runKm) else "")
            textSize = 16f
            setTextColor(mainColor)
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        return card
    }

    private fun sleepRow(s: SleepSessionRecord): View {
        val start = s.startTime.atZone(zone).format(timeFmt)
        val end = s.endTime.atZone(zone).format(timeFmt)
        val total = asleepMinutes(s)

        var detail = ""
        if (s.stages.isNotEmpty()) {
            fun m(type: Int) = s.stages.filter { it.stage == type }
                .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
            val deep = m(SleepSessionRecord.STAGE_TYPE_DEEP)
            val rem = m(SleepSessionRecord.STAGE_TYPE_REM)
            val light = m(SleepSessionRecord.STAGE_TYPE_LIGHT)
            val awake = m(SleepSessionRecord.STAGE_TYPE_AWAKE)
            if (deep + rem + light > 0) {
                detail = "深い ${fmtMinutes(deep)} ・ レム ${fmtMinutes(rem)} ・ 浅い ${fmtMinutes(light)}" +
                    (if (awake > 0) " ・ 覚醒 ${fmtMinutes(awake)}" else "")
            }
        }
        return row(sleepColor, "睡眠", "$start〜$end", fmtMinutes(total), detail)
    }

    private fun exerciseRow(e: ExerciseSessionRecord, km: Double?): View {
        val start = e.startTime.atZone(zone).format(timeFmt)
        val end = e.endTime.atZone(zone).format(timeFmt)
        val mins = Duration.between(e.startTime, e.endTime).toMinutes()
        val name = exerciseName(e.exerciseType)
        var detail = ""
        if (km != null) {
            detail = String.format(Locale.JAPAN, "%.2f km", km)
            if (isRun(e.exerciseType) && km > 0.3) {
                val paceSec = (Duration.between(e.startTime, e.endTime).seconds / km).toLong()
                detail += String.format(Locale.JAPAN, " ・ %d'%02d\"/km", paceSec / 60, paceSec % 60)
            }
        }
        return row(runColor, name, "$start〜$end", fmtMinutes(mins), detail)
    }

    /** 覚醒時間を除いた睡眠時間（段階データがなければ全体） */
    private fun asleepMinutes(s: SleepSessionRecord): Long {
        val total = Duration.between(s.startTime, s.endTime).toMinutes()
        val awake = s.stages.filter {
            it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE ||
                it.stage == SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
        }.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
        return (total - awake).coerceAtLeast(0)
    }

    private fun isRun(type: Int) =
        type == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING ||
            type == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL

    private fun exerciseName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "ランニング"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "トレッドミル"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "ウォーキング"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "ハイキング"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "サイクリング"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "エアロバイク"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "水泳"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "筋トレ"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "ヨガ"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "クロストレーナー"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
        else -> "運動"
    }

    private fun fmtMinutes(min: Long): String =
        if (min >= 60) "${min / 60}時間${min % 60}分" else "${min}分"

    // ---- 部品 ----

    private fun row(color: Int, kind: String, time: String, length: String, detail: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        box.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(color); cornerRadius = dp(2).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            marginEnd = dp(10)
        })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = "$kind  $length"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(mainColor)
        })
        texts.addView(TextView(this).apply {
            text = if (detail.isEmpty()) time else "$time\n$detail"
            textSize = 13f
            setTextColor(subColor)
        })
        box.addView(texts)
        return box
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(14))
        background = GradientDrawable().apply {
            setColor(cardColor); cornerRadius = dp(14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(subColor)
        setPadding(0, 0, 0, dp(4))
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTextColor(mainColor)
        setPadding(dp(4), dp(20), dp(4), 0)
    }

    private fun showMessage(msg: String, buttonText: String? = null, action: (() -> Unit)? = null) {
        list.removeAllViews()
        list.addView(note(msg))
        if (buttonText != null && action != null) {
            list.addView(Button(this).apply {
                text = buttonText
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
        }
    }

    private fun openStore() {
        val uri = Uri.parse(
            "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"
        )
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
        } catch (e: Exception) { }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
