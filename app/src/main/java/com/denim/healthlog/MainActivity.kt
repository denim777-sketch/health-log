package com.denim.healthlog

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.content.pm.PackageManager
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
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.reflect.KClass

class MainActivity : ComponentActivity() {

    private val sleepPerm = HealthPermission.getReadPermission(SleepSessionRecord::class)
    private val exercisePerm = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    private val distancePerm = HealthPermission.getReadPermission(DistanceRecord::class)
    // 30日より前の記録を読むための許可
    private val historyPerm = "android.permission.health.READ_HEALTH_DATA_HISTORY"
    private val weightPerm = HealthPermission.getReadPermission(WeightRecord::class)
    private val allPerms = setOf(sleepPerm, exercisePerm, distancePerm, historyPerm, weightPerm)

    private val zone: ZoneId = ZoneId.systemDefault()
    private val timeFmt = DateTimeFormatter.ofPattern("H:mm")
    private val dayFmt = DateTimeFormatter.ofPattern("M月d日（E）", Locale.JAPANESE)
    private val monthFmt = DateTimeFormatter.ofPattern("yyyy年M月", Locale.JAPANESE)

    private lateinit var list: LinearLayout
    private lateinit var dailyTab: Button
    private lateinit var monthlyTab: Button
    private var client: HealthConnectClient? = null

    // 読み込んだデータ（タブ切り替えで使い回す）
    private var sleeps: List<SleepSessionRecord> = emptyList()
    private var exercises: List<ExerciseSessionRecord> = emptyList()
    private var distances: Map<String, Double> = emptyMap()
    private var weights: List<WeightRecord> = emptyList()
    private var hasHistory = true
    private var hasWeight = true
    private val sourceNames = mutableMapOf<String, String>()
    private var loaded = false
    private var showMonthly = false

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
    private val lineColor get() = if (isDark) Color.parseColor("#33383E") else Color.parseColor("#DDE1E5")
    private val sleepColor = Color.parseColor("#6C7FD8")
    private val runColor = Color.parseColor("#E07A3F")
    private val weightColor = Color.parseColor("#3FA77A")

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

        // 「日別」「月別」の切り替え
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        dailyTab = Button(this).apply {
            text = "日別（30日）"
            setOnClickListener { showMonthly = false; refreshView() }
        }
        monthlyTab = Button(this).apply {
            text = "月別（12ヵ月）"
            setOnClickListener { showMonthly = true; refreshView() }
        }
        tabs.addView(dailyTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabs.addView(monthlyTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabs)
        updateTabs()

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

    private fun updateTabs() {
        dailyTab.alpha = if (showMonthly) 0.5f else 1f
        monthlyTab.alpha = if (showMonthly) 1f else 0.5f
    }

    private fun refreshView() {
        updateTabs()
        if (loaded) {
            if (showMonthly) renderMonthly() else renderDaily()
        }
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
                    loaded = false
                    showMessage(
                        "睡眠と運動の記録を読むには、ヘルスコネクトでの許可が必要です。\n" +
                            "「過去のデータへのアクセス」も許可すると、12ヵ月分の月別集計が見られます。",
                        "許可する"
                    ) { requestPermissions.launch(allPerms) }
                    return@launch
                }
                showMessage("読み込み中…")
                hasHistory = granted.contains(historyPerm)
                hasWeight = granted.contains(weightPerm)

                // 今月を含めて12ヵ月前の1日から
                val end = Instant.now()
                val start = YearMonth.now(zone).minusMonths(11).atDay(1).atStartOfDay(zone).toInstant()
                val range = TimeRangeFilter.between(start, end)

                sleeps = readAll(hc, SleepSessionRecord::class, range)
                exercises = readAll(hc, ExerciseSessionRecord::class, range)
                weights = if (hasWeight) readAll(hc, WeightRecord::class, range) else emptyList()

                // 重複を避けるため、データ元を1つに絞る
                sleeps = onlyFrom(sleeps, SLEEP_EXERCISE_SOURCE) { it.metadata }
                exercises = onlyFrom(exercises, SLEEP_EXERCISE_SOURCE) { it.metadata }
                weights = onlyFrom(weights, WEIGHT_SOURCE) { it.metadata }

                val dist = mutableMapOf<String, Double>()
                if (granted.contains(distancePerm)) {
                    for (ex in exercises.filter { hasDistance(it.exerciseType) }) {
                        try {
                            val res = hc.aggregate(
                                AggregateRequest(
                                    metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                                    timeRangeFilter = TimeRangeFilter.between(ex.startTime, ex.endTime)
                                )
                            )
                            val km = res[DistanceRecord.DISTANCE_TOTAL]?.inKilometers
                            if (km != null && km > 0.05) dist[ex.metadata.id] = km
                        } catch (e: Exception) {
                            // 距離が取れなくても一覧は表示する
                        }
                    }
                }
                distances = dist
                loaded = true
                refreshView()
            } catch (e: Exception) {
                showMessage("読み込みに失敗しました：${e.message}", "もう一度") { loadData() }
            }
        }
    }

    private suspend fun <T : Record> readAll(
        hc: HealthConnectClient,
        type: KClass<T>,
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

    // ---- 集計 ----

    private data class Stats(
        val nights: Int,
        val avgSleepMin: Long,
        val runs: Int,
        val runKm: Double,
        val workouts: Int,
        val workoutMin: Long,
        val avgWeight: Double?
    )

    /** 睡眠は「起きた日」、運動は「始めた日」が from〜to に入るものを集計 */
    private fun stats(from: LocalDate, to: LocalDate): Stats {
        fun inRange(d: LocalDate) = !d.isBefore(from) && !d.isAfter(to)
        val nightsMap = sleeps.filter { inRange(it.endTime.atZone(zone).toLocalDate()) }
            .groupBy { it.endTime.atZone(zone).toLocalDate() }
        val sleepTotal = nightsMap.values.sumOf { n -> n.sumOf { asleepMinutes(it) } }
        val ex = exercises.filter { inRange(it.startTime.atZone(zone).toLocalDate()) }
        val runs = ex.filter { isRun(it.exerciseType) }
        return Stats(
            nights = nightsMap.size,
            avgSleepMin = if (nightsMap.isEmpty()) 0 else sleepTotal / nightsMap.size,
            runs = runs.size,
            runKm = runs.sumOf { distances[it.metadata.id] ?: 0.0 },
            workouts = ex.size,
            workoutMin = ex.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() },
            avgWeight = weights.filter { inRange(it.time.atZone(zone).toLocalDate()) }
                .map { it.weight.inKilograms }.takeIf { it.isNotEmpty() }?.average()
        )
    }

    // ---- 日別 ----

    private fun renderDaily() {
        list.removeAllViews()
        val today = LocalDate.now(zone)

        // 直近7日と直近30日を並べて表示
        val s7 = stats(today.minusDays(6), today)
        val s30 = stats(today.minusDays(29), today)
        val sum = card()
        sum.addView(compareRow("", "直近7日", "直近30日", header = true))
        sum.addView(compareRow("平均睡眠", fmtSleep(s7), fmtSleep(s30)))
        sum.addView(compareRow("ラン回数", "${s7.runs}回", "${s30.runs}回"))
        sum.addView(compareRow("ラン距離", fmtKm(s7.runKm), fmtKm(s30.runKm)))
        sum.addView(compareRow("運動時間", fmtMinutes(s7.workoutMin), fmtMinutes(s30.workoutMin)))
        sum.addView(compareRow("平均体重", fmtKg(s7.avgWeight), fmtKg(s30.avgWeight)))
        list.addView(sum)

        if (!hasWeight) {
            list.addView(Button(this).apply {
                text = "体重の読み取りを許可"
                setOnClickListener { requestPermissions.launch(allPerms) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
        }

        val from = today.minusDays(29)
        val sleepByDay = sleeps.groupBy { it.endTime.atZone(zone).toLocalDate() }
            .filterKeys { !it.isBefore(from) }
        val exByDay = exercises.groupBy { it.startTime.atZone(zone).toLocalDate() }
            .filterKeys { !it.isBefore(from) }
        val weightByDay = weights.groupBy { it.time.atZone(zone).toLocalDate() }
            .filterKeys { !it.isBefore(from) }
        val days = (sleepByDay.keys + exByDay.keys + weightByDay.keys).sortedDescending()

        if (days.isEmpty()) {
            list.addView(note(
                "直近30日の記録が見つかりませんでした。\n\n" +
                    "Google Healthアプリ側で、ヘルスコネクトへの共有（睡眠・エクササイズ）がオンになっているか確認してください。"
            ))
            return
        }

        for (day in days) {
            val c = card()
            c.addView(TextView(this).apply {
                text = day.format(dayFmt)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(mainColor)
            })
            weightByDay[day].orEmpty().sortedBy { it.time }.forEach { c.addView(weightRow(it)) }
            sleepByDay[day].orEmpty().sortedBy { it.startTime }.forEach { c.addView(sleepRow(it)) }
            exByDay[day].orEmpty().sortedBy { it.startTime }.forEach {
                c.addView(exerciseRow(it, distances[it.metadata.id]))
            }
            list.addView(c)
        }
    }

    // ---- 月別 ----

    private fun renderMonthly() {
        list.removeAllViews()
        val thisMonth = YearMonth.now(zone)
        val today = LocalDate.now(zone)

        if (!hasHistory) {
            list.addView(note(
                "30日より前の記録を読む許可がないため、古い月は空になります。",
            ))
            list.addView(Button(this).apply {
                text = "過去データの読み取りを許可"
                setOnClickListener { requestPermissions.launch(allPerms) }
            })
        }

        // 12ヵ月の合計
        val first = thisMonth.minusMonths(11).atDay(1)
        val total = stats(first, today)
        val sum = card()
        sum.addView(label("過去12ヵ月の合計"))
        sum.addView(TextView(this).apply {
            text = "ランニング  ${total.runs}回・${fmtKm(total.runKm)}\n" +
                "運動  ${total.workouts}回・${fmtMinutes(total.workoutMin)}\n" +
                "平均睡眠  ${fmtSleep(total)}" +
                (if (total.avgWeight != null) "\n平均体重  ${fmtKg(total.avgWeight)}" else "")
            textSize = 16f
            setTextColor(mainColor)
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        list.addView(sum)

        // 月ごと（新しい月が上）
        for (i in 0 until 12) {
            val ym = thisMonth.minusMonths(i.toLong())
            val to = if (i == 0) today else ym.atEndOfMonth()
            val s = stats(ym.atDay(1), to)
            val c = card()
            c.addView(TextView(this).apply {
                text = ym.format(monthFmt) + if (i == 0) "（今月）" else ""
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(mainColor)
            })
            if (s.nights == 0 && s.workouts == 0 && s.avgWeight == null) {
                c.addView(TextView(this).apply {
                    text = "記録なし"
                    textSize = 13f
                    setTextColor(subColor)
                    setPadding(0, dp(6), 0, 0)
                })
            } else {
                c.addView(row(runColor, "ラン  ${s.runs}回・${fmtKm(s.runKm)}",
                    "運動合計 ${s.workouts}回・${fmtMinutes(s.workoutMin)}"))
                c.addView(row(sleepColor, "睡眠  平均${fmtSleep(s)}",
                    "記録 ${s.nights}日"))
                if (s.avgWeight != null) {
                    c.addView(row(weightColor, "体重  平均${fmtKg(s.avgWeight)}", ""))
                }
            }
            list.addView(c)
        }
    }

    // ---- 1件ごとの行 ----

    private fun sleepRow(s: SleepSessionRecord): View {
        val start = s.startTime.atZone(zone).format(timeFmt)
        val end = s.endTime.atZone(zone).format(timeFmt)
        var detail = "$start〜$end"
        if (s.stages.isNotEmpty()) {
            fun m(type: Int) = s.stages.filter { it.stage == type }
                .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
            val deep = m(SleepSessionRecord.STAGE_TYPE_DEEP)
            val rem = m(SleepSessionRecord.STAGE_TYPE_REM)
            val light = m(SleepSessionRecord.STAGE_TYPE_LIGHT)
            val awake = m(SleepSessionRecord.STAGE_TYPE_AWAKE)
            if (deep + rem + light > 0) {
                detail += "\n深い ${fmtMinutes(deep)} ・ レム ${fmtMinutes(rem)} ・ 浅い ${fmtMinutes(light)}" +
                    (if (awake > 0) " ・ 覚醒 ${fmtMinutes(awake)}" else "")
            }
        }
        detail += "\n" + sourceName(s.metadata)
        return row(sleepColor, "睡眠  ${fmtMinutes(asleepMinutes(s))}", detail)
    }

    private fun exerciseRow(e: ExerciseSessionRecord, km: Double?): View {
        val start = e.startTime.atZone(zone).format(timeFmt)
        val end = e.endTime.atZone(zone).format(timeFmt)
        val mins = Duration.between(e.startTime, e.endTime).toMinutes()
        var detail = "$start〜$end"
        if (km != null) {
            detail += "\n" + String.format(Locale.JAPAN, "%.2f km", km)
            if (isRun(e.exerciseType) && km > 0.3) {
                val paceSec = (Duration.between(e.startTime, e.endTime).seconds / km).toLong()
                detail += String.format(Locale.JAPAN, " ・ %d'%02d\"/km", paceSec / 60, paceSec % 60)
            }
        }
        detail += "\n" + sourceName(e.metadata)
        return row(runColor, "${exerciseName(e.exerciseType)}  ${fmtMinutes(mins)}", detail)
    }

    private fun weightRow(w: WeightRecord): View {
        val t = w.time.atZone(zone).format(timeFmt)
        return row(weightColor, "体重  ${fmtKg(w.weight.inKilograms)}", "$t\n" + sourceName(w.metadata))
    }

    /**
     * 指定したアプリ（名前で指定）の記録だけを残す。
     * そのアプリの記録が1件もない場合は、何も表示されなくなるのを避けるため全件を残す。
     */
    private fun <T> onlyFrom(records: List<T>, appName: String, meta: (T) -> Metadata): List<T> {
        val picked = records.filter { appLabel(meta(it)).equals(appName, ignoreCase = true) }
        return if (picked.isEmpty()) records else picked
    }

    private fun sourceName(m: Metadata) = "データ元：" + appLabel(m)

    /** 記録を書き込んだアプリの名前（分からなければパッケージ名） */
    private fun appLabel(m: Metadata): String {
        val pkg = m.dataOrigin.packageName
        return sourceNames.getOrPut(pkg) {
            try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(info).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                pkg
            }
        }
    }

    companion object {
        // 使うデータ元のアプリ名（データ元の表示と同じ名前）
        const val SLEEP_EXERCISE_SOURCE = "Health"
        const val WEIGHT_SOURCE = "HealthPlanet"
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

    private fun hasDistance(type: Int) = isRun(type) ||
        type == ExerciseSessionRecord.EXERCISE_TYPE_WALKING ||
        type == ExerciseSessionRecord.EXERCISE_TYPE_HIKING ||
        type == ExerciseSessionRecord.EXERCISE_TYPE_BIKING ||
        type == ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL ||
        type == ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER

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

    private fun fmtSleep(s: Stats) = if (s.nights > 0) fmtMinutes(s.avgSleepMin) else "—"

    private fun fmtKg(kg: Double?) =
        if (kg == null) "—" else String.format(Locale.JAPAN, "%.1f kg", kg)

    private fun fmtKm(km: Double) = String.format(Locale.JAPAN, "%.1f km", km)

    // ---- 部品 ----

    private fun compareRow(name: String, a: String, b: String, header: Boolean = false): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(if (header) 0 else 8), 0, dp(if (header) 6 else 0))
        }
        fun cell(t: String, weight: Float, color: Int, bold: Boolean, alignEnd: Boolean) =
            TextView(this).apply {
                text = t
                textSize = if (header) 13f else 15f
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                gravity = if (alignEnd) Gravity.END else Gravity.START
            }.also {
                r.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight))
            }
        cell(name, 1f, subColor, false, false)
        cell(a, 1.1f, if (header) subColor else mainColor, !header, true)
        cell(b, 1.1f, if (header) subColor else mainColor, !header, true)
        if (header) {
            val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            wrap.addView(r)
            wrap.addView(View(this).apply { setBackgroundColor(lineColor) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            return wrap
        }
        return r
    }

    private fun row(color: Int, title: String, detail: String): View {
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
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(mainColor)
        })
        texts.addView(TextView(this).apply {
            text = detail
            if (detail.isEmpty()) visibility = View.GONE
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
        } catch (e: Exception) {
            // ストアが開けない端末では何もしない
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
