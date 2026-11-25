package com.example.lockschedule

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CalendarContract
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class CalendarSource(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String
) {
    fun label(): String = "$displayName ($accountName)"
}

data class ScheduleFilterConfig(
    val selectedCalendarIds: Set<Long>,
    val hideAllDay: Boolean,
    val excludedKeywords: List<String>
)

object CalendarFilterPrefs {
    private const val PREFS_NAME = "calendar_filter_prefs"
    private const val KEY_SELECTED_IDS = "selected_calendar_ids"
    private const val KEY_HAS_CUSTOM_SELECTION = "has_custom_selection"
    private const val KEY_HIDE_ALL_DAY = "hide_all_day"
    private const val KEY_EXCLUDED_KEYWORDS = "excluded_keywords"

    fun getAvailableCalendars(context: Context): List<CalendarSource> {
        val result = mutableListOf<CalendarSource>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE
        )

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            "${CalendarContract.Calendars.ACCOUNT_NAME} ASC, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(CalendarContract.Calendars._ID)
            val nameIdx = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accNameIdx = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
            val accTypeIdx = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val name = cursor.getString(nameIdx) ?: "(이름 없음)"
                val accName = cursor.getString(accNameIdx) ?: "로컬"
                val accType = cursor.getString(accTypeIdx) ?: ""
                result.add(CalendarSource(id, name, accName, accType))
            }
        }
        return result
    }

    fun getFilterConfig(context: Context): ScheduleFilterConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasCustom = prefs.getBoolean(KEY_HAS_CUSTOM_SELECTION, false)
        val allIds = getAvailableCalendars(context).map { it.id }.toSet()

        val selectedIds = if (!hasCustom) {
            allIds
        } else {
            val saved = prefs.getStringSet(KEY_SELECTED_IDS, emptySet()).orEmpty()
            saved.mapNotNull { it.toLongOrNull() }.intersect(allIds)
        }

        val hideAllDay = prefs.getBoolean(KEY_HIDE_ALL_DAY, false)
        val rawKeywords = prefs.getString(KEY_EXCLUDED_KEYWORDS, "").orEmpty()
        val keywords = rawKeywords.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return ScheduleFilterConfig(
            selectedCalendarIds = selectedIds,
            hideAllDay = hideAllDay,
            excludedKeywords = keywords
        )
    }

    fun getRawKeywordsText(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_EXCLUDED_KEYWORDS, "").orEmpty()
    }

    fun saveFilterConfig(
        context: Context,
        selectedIds: Set<Long>,
        hideAllDay: Boolean,
        rawKeywords: String
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_HAS_CUSTOM_SELECTION, true)
            .putStringSet(KEY_SELECTED_IDS, selectedIds.map { it.toString() }.toSet())
            .putBoolean(KEY_HIDE_ALL_DAY, hideAllDay)
            .putString(KEY_EXCLUDED_KEYWORDS, rawKeywords.trim())
            .apply()
    }
}

data class ScheduleItem(
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val allDay: Boolean,
    val accountName: String,
    val calendarId: Long,
    val isTomorrow: Boolean = false
) {
    fun formattedTime(): String {
        if (allDay) return "종일"
        val sdf = SimpleDateFormat("HH:mm", Locale.KOREA)
        return "${sdf.format(Date(startTime))} ~ ${sdf.format(Date(endTime))}"
    }
}

data class ScheduleBundle(
    val todaySchedules: List<ScheduleItem>,
    val tomorrowSchedules: List<ScheduleItem>
)

object CalendarHelper {

    fun getTodayAndTomorrowSchedules(context: Context): ScheduleBundle {
        val todayList = mutableListOf<ScheduleItem>()
        val tomorrowList = mutableListOf<ScheduleItem>()
        val now = System.currentTimeMillis()
        val config = CalendarFilterPrefs.getFilterConfig(context)

        if (config.selectedCalendarIds.isEmpty()) {
            return ScheduleBundle(emptyList(), emptyList())
        }

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfToday = cal.timeInMillis

        cal.add(Calendar.DAY_OF_YEAR, 1)
        val startOfTomorrow = cal.timeInMillis

        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val endOfTomorrow = cal.timeInMillis

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startOfToday)
        ContentUris.appendId(builder, endOfTomorrow)

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.ACCOUNT_NAME,
            CalendarContract.Instances.CALENDAR_ID
        )

        val sortOrder = "${CalendarContract.Instances.BEGIN} ASC"

        context.contentResolver.query(
            builder.build(),
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
            val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
            val endIdx = cursor.getColumnIndex(CalendarContract.Instances.END)
            val allDayIdx = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
            val accountIdx = cursor.getColumnIndex(CalendarContract.Instances.ACCOUNT_NAME)
            val calIdIdx = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_ID)

            while (cursor.moveToNext()) {
                val calId = cursor.getLong(calIdIdx)
                if (calId !in config.selectedCalendarIds) continue

                val allDay = cursor.getInt(allDayIdx) == 1
                if (config.hideAllDay && allDay) continue

                val title = cursor.getString(titleIdx) ?: "(제목 없음)"
                val isExcludedByKeyword = config.excludedKeywords.any { keyword ->
                    title.contains(keyword, ignoreCase = true)
                }
                if (isExcludedByKeyword) continue

                val begin = cursor.getLong(beginIdx)
                val end = cursor.getLong(endIdx)
                val account = cursor.getString(accountIdx) ?: ""

                if (begin < startOfTomorrow) {
                    if (allDay || end >= now) {
                        todayList.add(ScheduleItem(title, begin, end, allDay, account, calId, isTomorrow = false))
                    }
                } else {
                    tomorrowList.add(ScheduleItem(title, begin, end, allDay, account, calId, isTomorrow = true))
                }
            }
        }
        return ScheduleBundle(todayList, tomorrowList)
    }
}

data class WeatherDustInfo(
    val currentTemp: Int,
    val maxTemp: Int,
    val minTemp: Int,
    val conditionText: String,
    val pm10Value: Int,
    val pm10Grade: String
) {
    fun summaryLine(): String =
        "현재 ${currentTemp}°C (${conditionText}) · 최고 ${maxTemp}°C / 최저 ${minTemp}°C · 미세먼지 ${pm10Value}㎍/㎥(${pm10Grade})"
}

object WeatherDustHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // 기본 좌표: 경북 구미시
    private const val DEFAULT_LAT = 36.1195
    private const val DEFAULT_LON = 128.3446

    suspend fun fetchTodayWeatherAndDust(context: Context): WeatherDustInfo? =
        withContext(Dispatchers.IO) {
            try {
                val (lat, lon) = getCurrentCoordinates(context)

                val weatherUrl =
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=Asia%2FSeoul"

                val airUrl =
                    "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon" +
                        "&current=pm10&timezone=Asia%2FSeoul"

                val weatherJson = JSONObject(getBody(weatherUrl))
                val airJson = JSONObject(getBody(airUrl))

                val currentObj = weatherJson.getJSONObject("current")
                val dailyObj = weatherJson.getJSONObject("daily")

                val currentTemp = Math.round(currentObj.getDouble("temperature_2m")).toInt()
                val weatherCode = currentObj.getInt("weather_code")
                val maxTemp = Math.round(dailyObj.getJSONArray("temperature_2m_max").getDouble(0)).toInt()
                val minTemp = Math.round(dailyObj.getJSONArray("temperature_2m_min").getDouble(0)).toInt()

                val pm10Value = Math.round(
                    airJson.getJSONObject("current").optDouble("pm10", 0.0)
                ).toInt()

                WeatherDustInfo(
                    currentTemp = currentTemp,
                    maxTemp = maxTemp,
                    minTemp = minTemp,
                    conditionText = mapWeatherCode(weatherCode),
                    pm10Value = pm10Value,
                    pm10Grade = mapPm10Grade(pm10Value)
                )
            } catch (_: Exception) {
                null
            }
        }

    private fun getBody(url: String): String {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun getCurrentCoordinates(context: Context): Pair<Double, Double> {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return DEFAULT_LAT to DEFAULT_LON

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)

        return if (loc != null) loc.latitude to loc.longitude else DEFAULT_LAT to DEFAULT_LON
    }

    private fun mapWeatherCode(code: Int): String = when (code) {
        0 -> "맑음"
        1, 2 -> "구름 조금"
        3 -> "흐림"
        45, 48 -> "안개"
        51, 53, 55 -> "이슬비"
        61, 63, 65 -> "비"
        71, 73, 75 -> "눈"
        80, 81, 82 -> "소나기"
        95, 96, 99 -> "뇌우"
        else -> "보통"
    }

    private fun mapPm10Grade(pm10: Int): String = when {
        pm10 <= 30 -> "좋음"
        pm10 <= 80 -> "보통"
        pm10 <= 150 -> "나쁨"
        else -> "매우나쁨"
    }
}

class ScheduleSyncService : Service() {

    companion object {
        const val CHANNEL_ID = "lockscreen_schedule_channel"
        const val NOTI_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var periodicJob: Job? = null
    private lateinit var calendarObserver: ContentObserver
    private var latestWeather: WeatherDustInfo? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        calendarObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshNotification()
            }
        }
        contentResolver.registerContentObserver(
            CalendarContract.Events.CONTENT_URI,
            true,
            calendarObserver
        )

        periodicJob = serviceScope.launch {
            while (true) {
                latestWeather = WeatherDustHelper.fetchTodayWeatherAndDust(this@ScheduleSyncService)
                refreshNotification()
                delay(30 * 60 * 1000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTI_ID, buildScheduleNotification(latestWeather))
        serviceScope.launch {
            latestWeather = WeatherDustHelper.fetchTodayWeatherAndDust(this@ScheduleSyncService)
            refreshNotification()
        }
        return START_STICKY
    }

    private fun refreshNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTI_ID, buildScheduleNotification(latestWeather))
    }

    private fun buildScheduleNotification(weather: WeatherDustInfo?): Notification {
        val bundle = CalendarHelper.getTodayAndTomorrowSchedules(this)
        val todaySchedules = bundle.todaySchedules
        val tomorrowSchedules = bundle.tomorrowSchedules

        val titleText = when {
            todaySchedules.isNotEmpty() -> {
                val next = todaySchedules.first()
                "다음: [${next.formattedTime()}] ${next.title}"
            }
            tomorrowSchedules.isNotEmpty() -> {
                val firstTomorrow = tomorrowSchedules.first()
                "오늘 일정 끝 · 내일 첫 일정: [${firstTomorrow.formattedTime()}] ${firstTomorrow.title}"
            }
            else -> "오늘·내일 예정된 일정 없음"
        }

        val weatherLine = weather?.summaryLine() ?: "날씨·미세먼지 정보 불러오는 중..."

        val bigText = StringBuilder().apply {
            append(weatherLine)
            append("
────────────────────")
            if (todaySchedules.isNotEmpty()) {
                todaySchedules.take(4).forEach { item ->
                    append("
• ${item.formattedTime()}  ${item.title}")
                }
                if (todaySchedules.size > 4) {
                    append("
외 오늘 일정 ${todaySchedules.size - 4}건")
                }
                if (tomorrowSchedules.isNotEmpty()) {
                    val nextDayFirst = tomorrowSchedules.first()
                    append("

[내일 첫 일정] ${nextDayFirst.formattedTime()}  ${nextDayFirst.title}")
                }
            } else if (tomorrowSchedules.isNotEmpty()) {
                append("
오늘 일정이 모두 끝났습니다.")
                append("

[내일 첫 일정] ${tomorrowSchedules.first().formattedTime()}  ${tomorrowSchedules.first().title}")
                if (tomorrowSchedules.size > 1) {
                    append("
외 내일 일정 ${tomorrowSchedules.size - 1}건")
                }
            } else {
                append("
오늘과 내일 예정된 일정이 없습니다.")
            }
        }

        val openCalendarIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_CALENDAR)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openCalendarIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(titleText)
            .setContentText(weatherLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText.toString()))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "잠금화면 스케줄·날씨 표시",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        periodicJob?.cancel()
        serviceScope.cancel()
        contentResolver.unregisterContentObserver(calendarObserver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class MainActivity : AppCompatActivity() {

    private lateinit var filterContainer: LinearLayout
    private lateinit var hideAllDayCheckBox: CheckBox
    private lateinit var keywordEditText: EditText
    private lateinit var previewText: TextView
    private val checkBoxes = mutableMapOf<Long, CheckBox>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.READ_CALENDAR] == true) {
            renderFilterOptions()
            startScheduleService()
            refreshPreview()
        } else {
            Toast.makeText(this, "캘린더 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 80, 50, 80)
        }

        val title = TextView(this).apply {
            text = "잠금화면 스케줄 · 날씨"
            textSize = 22f
        }

        val calSectionTitle = TextView(this).apply {
            text = "
1. 표시할 캘린더 선택 (구글 · 삼성)"
            textSize = 16f
            setPadding(0, 20, 0, 10)
        }

        filterContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val detailSectionTitle = TextView(this).apply {
            text = "
2. 세부 일정 필터"
            textSize = 16f
            setPadding(0, 20, 0, 10)
        }

        hideAllDayCheckBox = CheckBox(this).apply {
            text = "종일 일정 숨기기 (공휴일 · 기념일 등)"
        }

        val keywordLabel = TextView(this).apply {
            text = "제외할 키워드 (쉼표로 구분)"
            textSize = 14f
            setPadding(0, 16, 0, 6)
        }

        keywordEditText = EditText(this).apply {
            hint = "예: 점심, 개인, 생일, 반차"
            isSingleLine = true
        }

        val btnApply = Button(this).apply {
            text = "필터 저장 및 잠금화면 갱신"
            setOnClickListener { saveFilterAndRefresh() }
        }

        val btnStop = Button(this).apply {
            text = "표시 중지"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScheduleSyncService::class.java))
                Toast.makeText(this@MainActivity, "잠금화면 표시가 중지되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        previewText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 40, 0, 0)
        }

        layout.addView(title)
        layout.addView(calSectionTitle)
        layout.addView(filterContainer)
        layout.addView(detailSectionTitle)
        layout.addView(hideAllDayCheckBox)
        layout.addView(keywordLabel)
        layout.addView(keywordEditText)
        layout.addView(btnApply)
        layout.addView(btnStop)
        layout.addView(previewText)

        scrollView.addView(layout)
        setContentView(scrollView)

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val perms = mutableListOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allGranted = perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            renderFilterOptions()
            startScheduleService()
            refreshPreview()
        } else {
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    private fun renderFilterOptions() {
        filterContainer.removeAllViews()
        checkBoxes.clear()

        val calendars = CalendarFilterPrefs.getAvailableCalendars(this)
        val config = CalendarFilterPrefs.getFilterConfig(this)

        if (calendars.isEmpty()) {
            filterContainer.addView(TextView(this).apply {
                text = "불러온 캘린더 계정이 없습니다."
            })
        } else {
            calendars.forEach { cal ->
                val cb = CheckBox(this).apply {
                    text = cal.label()
                    isChecked = cal.id in config.selectedCalendarIds
                }
                checkBoxes[cal.id] = cb
                filterContainer.addView(cb)
            }
        }

        hideAllDayCheckBox.isChecked = config.hideAllDay
        keywordEditText.setText(CalendarFilterPrefs.getRawKeywordsText(this))
    }

    private fun saveFilterAndRefresh() {
        val chosenIds = checkBoxes.filterValues { it.isChecked }.keys.toSet()
        CalendarFilterPrefs.saveFilterConfig(
            context = this,
            selectedIds = chosenIds,
            hideAllDay = hideAllDayCheckBox.isChecked,
            rawKeywords = keywordEditText.text.toString()
        )
        startScheduleService()
        refreshPreview()
        Toast.makeText(this, "필터가 저장되고 잠금화면이 갱신되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun startScheduleService() {
        val intent = Intent(this, ScheduleSyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun refreshPreview() {
        val bundle = CalendarHelper.getTodayAndTomorrowSchedules(this)
        val todayText = if (bundle.todaySchedules.isEmpty()) {
            "남은 오늘 일정 없음"
        } else {
            bundle.todaySchedules.joinToString("
") {
                "• [${it.formattedTime()}] ${it.title} (${it.accountName})"
            }
        }
        val tomorrowText = if (bundle.tomorrowSchedules.isEmpty()) {
            "내일 일정 없음"
        } else {
            val first = bundle.tomorrowSchedules.first()
            "내일 첫 일정: [${first.formattedTime()}] ${first.title}"
        }
        previewText.text = "오늘 일정:
$todayText

$tomorrowText"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, ScheduleSyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
