package com.example.offlinediary

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.random.Random

// --- МОДЕЛИ ---

data class DiaryEvent(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val date: String,
    val type: Int, // 0 = Событие, 1 = Перерыв
    val startTime: String,
    val endTime: String,
    val colorArgb: Int,
    val recurrenceType: Int,
    val customDaysOfWeek: List<Int>,
    val hasNotification: Boolean = false
)

fun DiaryEvent.occursOn(pageDate: LocalDate): Boolean {
    val cDate = LocalDate.parse(this.date)
    return when (this.recurrenceType) {
        1 -> cDate <= pageDate
        2 -> cDate <= pageDate && this.customDaysOfWeek.contains(pageDate.dayOfWeek.value)
        else -> cDate == pageDate
    }
}

fun DiaryEvent.isPast(pageDate: LocalDate): Boolean {
    if (this.type == 1) {
        val eParts = endTime.split(":")
        if (eParts.size != 2) return false
        val eDateTime = LocalDateTime.of(pageDate, LocalTime.of(eParts[0].toInt(), eParts[1].toInt()))
        return LocalDateTime.now().isAfter(eDateTime)
    } else {
        val tParts = startTime.split(":")
        if (tParts.size != 2) return false
        val eDateTime = LocalDateTime.of(pageDate, LocalTime.of(tParts[0].toInt(), tParts[1].toInt()))
        return LocalDateTime.now().isAfter(eDateTime)
    }
}

// --- ХРАНИЛИЩЕ ---

class DataManager(context: Context) {
    private val prefs = context.getSharedPreferences("DiaryPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    fun saveEvents(events: List<DiaryEvent>) = prefs.edit().putString("events", gson.toJson(events)).apply()
    fun loadEvents(): List<DiaryEvent> {
        val json = prefs.getString("events", null) ?: return emptyList()
        val type = object : TypeToken<List<DiaryEvent>>() {}.type
        return gson.fromJson(json, type)
    }
    fun isDarkTheme() = prefs.getBoolean("dark_theme", false)
    fun setDarkTheme(isDark: Boolean) = prefs.edit().putBoolean("dark_theme", isDark).apply()
    fun getDefaultBlockColor() = prefs.getInt("default_color", Color(0xFFFFFFFF).toArgb())
    fun setDefaultBlockColor(color: Int) = prefs.edit().putInt("default_color", color).apply()
}

// --- УВЕДОМЛЕНИЯ ---

const val CHANNEL_ID = "diary_reminders"

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Событие"
        val eventId = intent.getStringExtra("eventId") ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Напоминание")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(eventId.hashCode(), notification)
        } catch (e: SecurityException) { }
    }
}

fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(CHANNEL_ID, "Напоминания", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Уведомления о событиях"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

fun scheduleNotification(context: Context, event: DiaryEvent) {
    if (!event.hasNotification) return
    try {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) return

        val intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra("title", event.title)
            putExtra("eventId", event.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(context, event.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val tParts = event.startTime.split(":")
        if (tParts.size != 2) return
        val eventDateTime = LocalDateTime.of(LocalDate.parse(event.date), LocalTime.of(tParts[0].toInt(), tParts[1].toInt()))
        val millis = eventDateTime.minusMinutes(5).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        if (millis > System.currentTimeMillis())
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent)
    } catch (e: SecurityException) { }
}

fun cancelNotification(context: Context, event: DiaryEvent) {
    val intent = Intent(context, NotificationReceiver::class.java)
    val pendingIntent = PendingIntent.getBroadcast(context, event.id.hashCode(), intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent)
}

// --- АКТИВНОСТЬ ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel(this)
        val dataManager = DataManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        setContent {
            var isDarkTheme by remember { mutableStateOf(dataManager.isDarkTheme()) }
            var defaultBlockColor by remember { mutableStateOf(Color(dataManager.getDefaultBlockColor())) }

            val colorScheme = if (isDarkTheme) darkColorScheme(
                primary = Color(0xFF90CAF9),
                secondary = Color(0xFFCE93D8),
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E),
                surfaceVariant = Color(0xFF2C2C2C),
                onSurface = Color(0xFFE0E0E0),
                onBackground = Color(0xFFE0E0E0)
            ) else lightColorScheme(
                primary = Color(0xFF1976D2),
                secondary = Color(0xFF9C27B0),
                background = Color(0xFFF8F9FA),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFF5F5F5),
            )

            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DiaryApp(dataManager, isDarkTheme,
                        onThemeChange = { isDarkTheme = it; dataManager.setDarkTheme(it) },
                        defaultBlockColor = defaultBlockColor,
                        onDefaultColorChange = { defaultBlockColor = it; dataManager.setDefaultBlockColor(it.toArgb()) }
                    )
                }
            }
        }
    }
}

// --- ГЛАВНЫЙ ЭКРАН ---

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiaryApp(
    dataManager: DataManager, isDarkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit, defaultBlockColor: Color,
    onDefaultColorChange: (Color) -> Unit
) {
    val context = LocalContext.current
    val eventsList = remember { mutableStateListOf(*dataManager.loadEvents().toTypedArray()) }
    fun saveAndSync() = dataManager.saveEvents(eventsList)

    var isMonthView by remember { mutableStateOf(false) }
    var isSettingsView by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var isEditMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    val pageCount = 10000
    val initialPage = pageCount / 2
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pageCount })
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(selectedDate) {
        if (!isMonthView && !isSettingsView) {
            pagerState.scrollToPage(initialPage + ChronoUnit.DAYS.between(LocalDate.now(), selectedDate).toInt())
        }
    }

    when {
        isSettingsView -> SettingsScreen(isDarkTheme, onThemeChange, defaultBlockColor, onDefaultColorChange) { isSettingsView = false }
        isMonthView -> MonthCalendarView(selectedDate, eventsList,
            onDaySelected = { selectedDate = it; isMonthView = false },
            onBackClick = { isMonthView = false })
        else -> {
            Scaffold(
                topBar = {
                    val pageDate = LocalDate.now().plusDays((pagerState.currentPage - initialPage).toLong())
                    val fmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru"))
                    val activeTasks = eventsList.count { it.occursOn(pageDate) && it.type != 2 }

                    if (isSearchActive) {
                        TopAppBar(
                            title = {
                                TextField(value = searchQuery, onValueChange = { searchQuery = it },
                                    placeholder = { Text("Поиск...") }, singleLine = true,
                                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent)
                                )
                            },
                            navigationIcon = { IconButton(onClick = { isSearchActive = false; searchQuery = "" }) { Icon(Icons.Default.ArrowBack, "Назад") } }
                        )
                    } else {
                        CenterAlignedTopAppBar(
                            title = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.pointerInput(Unit) {
                                    detectTapGestures(onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); selectedDate = LocalDate.now() })
                                }) {
                                    Text(pageDate.format(fmt), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                    Text("$activeTasks событий", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { isEditMode = !isEditMode }) {
                                    Icon(if (isEditMode) Icons.Default.Done else Icons.Default.Edit, "Ред.",
                                        tint = if (isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                }
                            },
                            actions = {
                                IconButton(onClick = { isSearchActive = true }) { Icon(Icons.Default.Search, "Поиск") }
                                IconButton(onClick = { selectedDate = pageDate; isMonthView = true }) { Icon(Icons.Default.DateRange, "Календарь") }
                                IconButton(onClick = { isSettingsView = true }) { Icon(Icons.Default.Settings, "Настройки") }
                            },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }
            ) { padding ->
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(padding)) { page ->
                    val pageDate = LocalDate.now().plusDays((page - initialPage).toLong())
                    var showAddDialog by remember { mutableStateOf(false) }
                    var eventToEdit by remember { mutableStateOf<DiaryEvent?>(null) }

                    val dailyEvents = remember(eventsList.toList(), pageDate, searchQuery) {
                        eventsList.filter { it.occursOn(pageDate) && it.type != 2 &&
                            (searchQuery.isEmpty() || it.title.contains(searchQuery, true))
                        }.sortedBy { it.startTime }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        // Таймер до ближайшего события
                        val now = LocalDateTime.now()
                        val upcoming = dailyEvents.firstOrNull {
                            val tParts = it.startTime.split(":")
                            if (tParts.size < 2) return@firstOrNull false
                            val eDateTime = LocalDateTime.of(pageDate, LocalTime.of(tParts[0].toInt(), tParts[1].toInt()))
                            ChronoUnit.SECONDS.between(now, eDateTime) in 1..86400
                        }

                        if (upcoming != null && pageDate == LocalDate.now() && !isEditMode && searchQuery.isEmpty()) {
                            item { CountdownBanner(upcoming, pageDate) }
                            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant) }
                        }

                        items(dailyEvents, key = { it.id }) { event ->
                            val isPast = event.isPast(pageDate)
                            EventCard(event, pageDate, defaultBlockColor, isDarkTheme, isPast, isEditMode) {
                                eventToEdit = event
                                showAddDialog = true
                            }
                        }

                        if (isEditMode) {
                            item {
                                OutlinedButton(
                                    onClick = { eventToEdit = null; showAddDialog = true },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(Icons.Default.Add, "Добавить", modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Новое событие", fontSize = 16.sp)
                                }
                            }
                            item { Spacer(Modifier.height(16.dp)) }
                        }
                    }

                    if (showAddDialog) {
                        AddEventDialog(date = pageDate, event = eventToEdit,
                            onDismiss = { showAddDialog = false; eventToEdit = null },
                            onDelete = {
                                eventToEdit?.let { cancelNotification(context, it); eventsList.remove(it); saveAndSync() }
                                showAddDialog = false
                            },
                            onSave = { event ->
                                eventToEdit?.let { eventsList.remove(it) }
                                eventsList.add(event)
                                scheduleNotification(context, event)
                                saveAndSync()
                                showAddDialog = false
                            }
                        )
                    }
                }
            }
        }
    }
}

// --- БАННЕР ОБРАТНОГО ОТСЧЁТА ---

@Composable
fun CountdownBanner(event: DiaryEvent, pageDate: LocalDate) {
    var ticks by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); ticks++ } }

    val tParts = event.startTime.split(":")
    val eDateTime = LocalDateTime.of(pageDate, LocalTime.of(tParts[0].toInt(), tParts[1].toInt()))
    val totalSeconds = ChronoUnit.SECONDS.between(LocalDateTime.now(), eDateTime)

    val formattedTime = remember(ticks) {
        if (totalSeconds > 0) {
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            String.format("%02d:%02d:%02d", h, m, s)
        } else "00:00:00"
    }

    if (totalSeconds > 0) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(event.colorArgb).copy(alpha = 0.15f))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Schedule, "Таймер", tint = Color(event.colorArgb), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Следующее событие", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text(event.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(formattedTime, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(event.colorArgb))
            }
        }
    }
}

// --- КАРТОЧКА СОБЫТИЯ ---

@Composable
fun EventCard(
    event: DiaryEvent, pageDate: LocalDate, defaultBg: Color,
    isDarkTheme: Boolean, isPast: Boolean, isEditMode: Boolean,
    onEditClick: () -> Unit
) {
    val lineColor = if (isPast) Color(event.colorArgb).copy(alpha = 0.3f) else Color(event.colorArgb)
    val cardAlpha = if (isPast) 0.6f else 1f
    val isBreak = event.type == 1

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDarkTheme) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = cardAlpha)
            else defaultBg.copy(alpha = cardAlpha)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isPast) 0.dp else 1.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Цветная боковая линия
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .defaultMinSize(minHeight = 70.dp)
                    .background(lineColor, RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp))
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isBreak) "⏸ ${event.title}" else event.title,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isPast) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (event.hasNotification && !isBreak && !isPast) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Default.Notifications, null,
                                    tint = Color(event.colorArgb),
                                    modifier = Modifier.size(16.dp))
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        // Время
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Schedule, "Время",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            Spacer(Modifier.width(4.dp))
                            val timeStr = if (isBreak && event.endTime.isNotEmpty() && event.endTime != event.startTime)
                                "${event.startTime} — ${event.endTime}"
                            else event.startTime
                            Text(timeStr, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))

                            val recStr = when(event.recurrenceType) {
                                1 -> " • Ежедневно"
                                2 -> " • По дням"
                                else -> ""
                            }
                            if (recStr.isNotEmpty()) {
                                Text(recStr, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    if (isEditMode && !isPast) {
                        IconButton(onClick = onEditClick) {
                            Icon(Icons.Default.Edit, "Изменить",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp))
                        }
                    }
                }

                // Прогресс-бар для перерыва
                if (isBreak && pageDate == LocalDate.now() && !isPast) {
                    val sParts = event.startTime.split(":")
                    val eParts = event.endTime.split(":")
                    if (sParts.size == 2 && eParts.size == 2) {
                        val sTime = LocalTime.of(sParts[0].toInt(), sParts[1].toInt())
                        val eTime = LocalTime.of(eParts[0].toInt(), eParts[1].toInt())
                        val nowTime = LocalTime.now()
                        if (nowTime.isAfter(sTime) && nowTime.isBefore(eTime)) {
                            Spacer(Modifier.height(8.dp))
                            val progress = ChronoUnit.MINUTES.between(sTime, nowTime).toFloat() /
                                    ChronoUnit.MINUTES.between(sTime, eTime).toFloat()
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = lineColor,
                                trackColor = lineColor.copy(alpha = 0.1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- ДИАЛОГ ДОБАВЛЕНИЯ (УЛУЧШЕННЫЙ) ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEventDialog(
    date: LocalDate, event: DiaryEvent?,
    onDismiss: () -> Unit, onDelete: () -> Unit,
    onSave: (DiaryEvent) -> Unit
) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(event?.title ?: "") }
    var type by remember { mutableStateOf(event?.type ?: 0) }
    var startTime by remember { mutableStateOf(event?.startTime ?: "12:00") }
    var endTime by remember { mutableStateOf(event?.endTime ?: "13:00") }
    var selectedColor by remember { mutableStateOf(Color(event?.colorArgb ?: 0xFF1976D2.toInt())) }
    var recType by remember { mutableStateOf(event?.recurrenceType ?: 0) }
    val customDays = remember { mutableStateListOf(*(event?.customDaysOfWeek?.toTypedArray() ?: emptyArray())) }
    var hasNotification by remember { mutableStateOf(event?.hasNotification ?: false) }
    var showEndTime by remember { mutableStateOf(event?.endTime?.isNotEmpty() == true && event.endTime != event.startTime) }

    val colors = listOf(
        Color(0xFF1976D2), Color(0xFFE53935), Color(0xFF43A047),
        Color(0xFFFB8C00), Color(0xFF8E24AA), Color(0xFF00ACC1),
        Color(0xFFEC407A), Color(0xFF795548)
    )
    val days = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

    fun pickTime(current: String, onPicked: (String) -> Unit) {
        val parts = current.split(":")
        TimePickerDialog(ctx, { _, h, m -> onPicked(String.format("%02d:%02d", h, m)) },
            parts[0].toInt(), parts[1].toInt(), true).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (event == null) "Новое событие" else "Изменить",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Название
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                // Тип события
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == 0,
                        onClick = { type = 0 },
                        label = { Text("📌 Событие") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = type == 1,
                        onClick = { type = 1 },
                        label = { Text("⏸ Перерыв") },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Время начала
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, "Начало", modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Начало:", modifier = Modifier.width(65.dp), fontSize = 15.sp)
                    TextButton(onClick = { pickTime(startTime) { startTime = it } }) {
                        Text(startTime, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }

                // Время окончания (опционально)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = showEndTime,
                        onCheckedChange = { showEndTime = it; if (!it) endTime = startTime },
                        modifier = Modifier.padding(start = 0.dp)
                    )
                    Text("Время окончания", fontSize = 14.sp)

                    if (showEndTime) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { pickTime(endTime) { endTime = it } }) {
                            Text(endTime, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // Цвет (только для события)
                if (type == 0) {
                    Text("Цвет:", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        colors.forEach { c ->
                            Box(
                                modifier = Modifier.size(36.dp).clip(CircleShape).background(c)
                                    .border(if (selectedColor == c) 3.dp else 0.dp, Color.White, CircleShape)
                                    .clickable { selectedColor = c }
                            )
                        }
                    }
                }

                // Уведомление
                if (type == 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { hasNotification = !hasNotification }
                            .padding(4.dp)
                    ) {
                        Checkbox(checked = hasNotification, onCheckedChange = { hasNotification = it })
                        Text("Уведомить за 5 минут", fontSize = 14.sp)
                    }
                }

                // Повторение
                Text("Повторение:", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = recType == 0, onClick = { recType = 0 }, label = { Text("Один раз") })
                    FilterChip(selected = recType == 1, onClick = { recType = 1 }, label = { Text("Ежедневно") })
                    FilterChip(selected = recType == 2, onClick = { recType = 2 }, label = { Text("По дням") })
                }

                // Дни недели
                AnimatedVisibility(visible = recType == 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        days.forEachIndexed { i, d ->
                            val dayNum = i + 1
                            FilterChip(
                                selected = customDays.contains(dayNum),
                                onClick = { if (customDays.contains(dayNum)) customDays.remove(dayNum) else customDays.add(dayNum) },
                                label = { Text(d, fontSize = 13.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onSave(DiaryEvent(
                            id = event?.id ?: UUID.randomUUID().toString(),
                            title = title, date = date.toString(), type = type,
                            startTime = startTime,
                            endTime = if (showEndTime) endTime else startTime,
                            colorArgb = selectedColor.toArgb(),
                            recurrenceType = recType,
                            customDaysOfWeek = customDays.toList(),
                            hasNotification = if (type == 0) hasNotification else false
                        ))
                    }
                },
                shape = RoundedCornerShape(12.dp)
            ) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (event != null) {
                    TextButton(onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Удалить") }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

// --- КАЛЕНДАРЬ ---

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MonthCalendarView(
    currentDate: LocalDate, allEvents: List<DiaryEvent>,
    onDaySelected: (LocalDate) -> Unit, onBackClick: () -> Unit
) {
    val pageCount = 1200
    val initialPage = pageCount / 2
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pageCount })

    LaunchedEffect(Unit) {
        pagerState.scrollToPage(initialPage + ChronoUnit.MONTHS.between(YearMonth.now(), YearMonth.from(currentDate)).toInt())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val ym = YearMonth.now().plusMonths((pagerState.currentPage - initialPage).toLong())
                    Text(
                        ym.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru"))).replaceFirstChar { it.uppercase() },
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = { IconButton(onClick = onBackClick) { Icon(Icons.Default.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { p ->
        HorizontalPager(state = pagerState, modifier = Modifier.padding(p).fillMaxSize()) { page ->
            val ym = YearMonth.now().plusMonths((page - initialPage).toLong())
            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Заголовки дней
                items(listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")) { day ->
                    Text(day, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                // Ячейки
                items((1..ym.lengthOfMonth()).toList()) { d ->
                    val date = ym.atDay(d)
                    val isSel = date == currentDate
                    val isToday = date == LocalDate.now()
                    val hasEvents = allEvents.any { it.occursOn(date) && it.type != 2 }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDaySelected(date) }
                            .padding(4.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isSel -> MaterialTheme.colorScheme.primary
                                        isToday -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                        else -> Color.Transparent
                                    }
                                )
                                .border(
                                    if (isToday && !isSel) 2.dp else 0.dp,
                                    MaterialTheme.colorScheme.primary,
                                    CircleShape
                                )
                        ) {
                            Text(d.toString(),
                                fontWeight = if (isSel || isToday) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp
                            )
                        }
                        if (hasEvents) {
                            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        } else {
                            Spacer(Modifier.height(5.dp))
                        }
                    }
                }
            }
        }
    }
}

// --- НАСТРОЙКИ (УЛУЧШЕННЫЕ) ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isDark: Boolean, onThemeChange: (Boolean) -> Unit,
    defaultColor: Color, onColorChange: (Color) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") } }
            )
        }
    ) { p ->
        Column(
            modifier = Modifier.padding(p).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Тема
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Тёмная тема", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Меняет оформление приложения", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Switch(checked = isDark, onCheckedChange = onThemeChange,
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
                }
            }

            // Цвет карточек
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Цвет карточек", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Используется для обычных событий", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOf(
                            Color(0xFFFFFFFF), Color(0xFFFFF3E0),
                            Color(0xFFE8F5E9), Color(0xFFE3F2FD),
                            Color(0xFFF3E5F5), Color(0xFFFFFDE7)
                        ).forEach { c ->
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(
                                        width = if (defaultColor == c) 3.dp else 1.dp,
                                        color = if (defaultColor == c) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                                        shape = CircleShape
                                    )
                                    .clickable { onColorChange(c) }
                            )
                        }
                    }
                }
            }

            // О приложении
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("О приложении", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Офлайн Дневник v1.0", fontSize = 14.sp)
                    Text("Планируйте события, перерывы и получайте уведомления",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }
}
