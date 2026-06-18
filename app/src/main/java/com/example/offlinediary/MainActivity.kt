package com.example.offlinediary
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.draw.drawBehind



// --- МОДЕЛИ И СОХРАНЕНИЕ ---

data class DiaryEvent(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val date: String,
    val type: Int, // 0 = Обычное, 1 = Перерыв (с концом), 2 = Пропуск (без времени, скрытое)
    val startTime: String,
    val endTime: String,
    val colorArgb: Int,
    val recurrenceType: Int, // 0 = 1 раз, 1 = Каждый день, 2 = Выбранные дни
    val customDaysOfWeek: List<Int>, // 1..7 (Пн..Вс)
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
    fun getDefaultBlockColor() = prefs.getInt("default_color", Color(0xFFF0F0F0).toArgb())
    fun setDefaultBlockColor(color: Int) = prefs.edit().putInt("default_color", color).apply()
}

// --- ОСНОВНАЯ АКТИВНОСТЬ ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dataManager = DataManager(this)

        setContent {
            var isDarkTheme by remember { mutableStateOf(dataManager.isDarkTheme()) }
            var defaultBlockColor by remember { mutableStateOf(Color(dataManager.getDefaultBlockColor())) }
            
            MaterialTheme(colorScheme = if (isDarkTheme) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DiaryApp(
                        dataManager = dataManager,
                        isDarkTheme = isDarkTheme,
                        onThemeChange = { isDarkTheme = it; dataManager.setDarkTheme(it) },
                        defaultBlockColor = defaultBlockColor,
                        onDefaultColorChange = { defaultBlockColor = it; dataManager.setDefaultBlockColor(it.toArgb()) }
                    )
                }
            }
        }
    }
}


// --- ГЛАВНЫЙ ИНТЕРФЕЙС ---

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiaryApp(dataManager: DataManager, isDarkTheme: Boolean, onThemeChange: (Boolean) -> Unit, defaultBlockColor: Color, onDefaultColorChange: (Color) -> Unit) {
    val eventsList = remember { mutableStateListOf(*dataManager.loadEvents().toTypedArray()) }
    fun saveAndSync() { dataManager.saveEvents(eventsList) }

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
            val diff = ChronoUnit.DAYS.between(LocalDate.now(), selectedDate).toInt()
            pagerState.scrollToPage(initialPage + diff)
        }
    }

    when {
        isSettingsView -> SettingsScreen(isDarkTheme, onThemeChange, defaultBlockColor, onDefaultColorChange) { isSettingsView = false }
        isMonthView -> MonthCalendarView(selectedDate, eventsList, { selectedDate = it; isMonthView = false }, { isMonthView = false })
        else -> {
            Scaffold(
                topBar = {
                    val pageDate = LocalDate.now().plusDays((pagerState.currentPage - initialPage).toLong())
                    val fmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru"))
                    // Игнорируем старые "пропуски" (type == 2)
                    val activeTasks = eventsList.count { it.occursOn(pageDate) && it.type != 2 }

                    if (isSearchActive) {
                        SearchBarTop(query = searchQuery, onQueryChange = { searchQuery = it }, onClose = { isSearchActive = false; searchQuery = "" })
                    } else {
                        CenterAlignedTopAppBar(
                            title = { 
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.pointerInput(Unit) {
                                    detectTapGestures(onLongPress = { 
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectedDate = LocalDate.now() 
                                    })
                                }) {
                                    Text(pageDate.format(fmt), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Text("$activeTasks задач", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { isEditMode = !isEditMode; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }) { 
                                    Icon(if (isEditMode) Icons.Default.Done else Icons.Default.Edit, contentDescription = "Редактировать", tint = if (isEditMode) MaterialTheme.colorScheme.primary else LocalContentColor.current) 
                                }
                            },
                            actions = {
                                IconButton(onClick = { isSearchActive = true }) { Icon(Icons.Default.Search, "Поиск") }
                                IconButton(onClick = { selectedDate = pageDate; isMonthView = true }) { Icon(Icons.Default.DateRange, "Календарь") }
                                IconButton(onClick = { isSettingsView = true }) { Icon(Icons.Default.Settings, "Настройки") }
                            }
                        )
                    }
                }
            ) { padding ->
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(padding)) { page ->
                    val pageDate = LocalDate.now().plusDays((page - initialPage).toLong())
                    
                    var showAddDialog by remember { mutableStateOf(false) }
                    var eventToEdit by remember { mutableStateOf<DiaryEvent?>(null) }

                    // Убрана тяжелая фильтрация, отсеиваем старые пропуски (type == 2)
                    val dailyEvents = eventsList.filter { 
                        it.occursOn(pageDate) && it.type != 2 && (searchQuery.isEmpty() || it.title.contains(searchQuery, true))
                    }.sortedBy { it.startTime }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp)
                    ) {
                        
                        val now = LocalDateTime.now()
                        val upcoming = dailyEvents.firstOrNull { 
                            val tParts = it.startTime.split(":")
                            if (tParts.size < 2) return@firstOrNull false
                            val eDateTime = LocalDateTime.of(pageDate, LocalTime.of(tParts[0].toInt(), tParts[1].toInt()))
                            val diff = ChronoUnit.SECONDS.between(now, eDateTime)
                            diff in 1..86400
                        }

                        if (upcoming != null && pageDate == LocalDate.now() && !isEditMode && searchQuery.isEmpty()) {
                            item { 
                                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    CountdownBlock(upcoming, pageDate) 
                                }
                            }
                        }

                        items(dailyEvents, key = { it.id }) { event ->
                            EventCard(event, pageDate, defaultBlockColor, isDarkTheme, isEditMode) {
                                eventToEdit = event
                                showAddDialog = true
                            }
                        }

                        if (isEditMode) {
                            item {
                                OutlinedButton(
                                    onClick = { eventToEdit = null; showAddDialog = true },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp).height(50.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Add, "Добавить")
                                    Spacer(Modifier.width(8.dp))
                                    Text("Добавить событие")
                                }
                            }
                        }
                    }

                    if (showAddDialog) {
                        AddEventDialog(
                            date = pageDate,
                            event = eventToEdit,
                            onDismiss = { showAddDialog = false; eventToEdit = null },
                            onDelete = {
                                eventsList.remove(eventToEdit)
                                saveAndSync()
                                showAddDialog = false
                            },
                            onSave = { event ->
                                eventToEdit?.let { eventsList.remove(it) }
                                eventsList.add(event)
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

@Composable
fun SearchBarTop(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Назад") }
            TextField(
                value = query, onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Поиск событий...") },
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
            )
        }
    }
}

@Composable
fun CountdownBlock(event: DiaryEvent, pageDate: LocalDate) {
    var ticks by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); ticks++ } }

    val tParts = event.startTime.split(":")
    val eDateTime = LocalDateTime.of(pageDate, LocalTime.of(tParts[0].toInt(), tParts[1].toInt()))
    val totalSeconds = ChronoUnit.SECONDS.between(LocalDateTime.now(), eDateTime)

    val formattedTime = remember(ticks) {
        if (totalSeconds > 0) {
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else "00:00:00"
    }

    if (totalSeconds > 0) {
        Card(
            modifier = Modifier.fillMaxWidth().height(45.dp).padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.width(6.dp).fillMaxHeight().background(Color(event.colorArgb)))
                Spacer(modifier = Modifier.width(12.dp))
                Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(event.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                Text(formattedTime, modifier = Modifier.padding(end = 12.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun EventCard(event: DiaryEvent, pageDate: LocalDate, defaultBg: Color, isDarkTheme: Boolean, isEditMode: Boolean, onEditClick: () -> Unit) {
    val isBreak = event.type == 1
    val bgColor = if (isBreak) Color.Transparent else (if (isDarkTheme) Color(0xFF2C2C2C) else defaultBg)
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // Высокопроизводительная отрисовка тонкой вертикальной линии
                drawLine(
                    color = Color.Gray.copy(alpha = 0.3f),
                    start = Offset(32.dp.toPx(), 0f),
                    end = Offset(32.dp.toPx(), size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
    ) {
        // Метка на таймлайне (кружок)
        Box(
            modifier = Modifier
                .width(64.dp)
                .padding(top = 28.dp), // Выравниваем по центру текста заголовка
            contentAlignment = Alignment.Center
        ) {
            if (!isBreak) {
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(Color(event.colorArgb)))
            } else {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).border(1.dp, Color.Gray, CircleShape).background(MaterialTheme.colorScheme.background))
            }
        }

        // Карточка события
        Card(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp)
                .padding(end = 16.dp),
            colors = CardDefaults.cardColors(containerColor = bgColor),
            shape = RoundedCornerShape(12.dp),
            border = if (isBreak) BorderStroke(1.dp, Color.Gray.copy(alpha = 0.2f)) else null,
            elevation = CardDefaults.cardElevation(defaultElevation = if (isBreak) 0.dp else 2.dp)
        ) {
            Column {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isBreak) "Перерыв: ${event.title}" else event.title, 
                                fontSize = 18.sp, 
                                fontWeight = FontWeight.Bold,
                                color = if (isBreak) Color.Gray else Color.Unspecified
                            )
                            if (event.hasNotification && !isBreak) {
                                Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp).size(16.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        val tStr = if (isBreak) "${event.startTime} - ${event.endTime}" else event.startTime
                        val rStr = when(event.recurrenceType) { 1 -> " • Каждый день"; 2 -> " • Выбранные дни"; else -> "" }
                        Text("$tStr$rStr", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    
                    if (isEditMode) {
                        IconButton(onClick = onEditClick) { Icon(Icons.Default.Edit, "Изменить", tint = MaterialTheme.colorScheme.primary) }
                    }
                }

                // Прогресс-бар для перерыва
                if (isBreak && pageDate == LocalDate.now()) {
                    val sParts = event.startTime.split(":")
                    val eParts = event.endTime.split(":")
                    if (sParts.size == 2 && eParts.size == 2) {
                        val sTime = LocalTime.of(sParts[0].toInt(), sParts[1].toInt())
                        val eTime = LocalTime.of(eParts[0].toInt(), eParts[1].toInt())
                        val nowTime = LocalTime.now()
                        
                        if (nowTime.isAfter(sTime) && nowTime.isBefore(eTime)) {
                            val totalMins = ChronoUnit.MINUTES.between(sTime, eTime).toFloat()
                            val passedMins = ChronoUnit.MINUTES.between(sTime, nowTime).toFloat()
                            val progress = if (totalMins > 0) passedMins / totalMins else 0f
                            
                            LinearProgressIndicator(
                                progress = progress, 
                                modifier = Modifier.fillMaxWidth().height(3.dp), 
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color.Transparent
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEventDialog(date: LocalDate, event: DiaryEvent?, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (DiaryEvent) -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(event?.title ?: "") }
    var type by remember { mutableStateOf(if (event?.type == 1) 1 else 0) } // Только 0=Событие, 1=Перерыв
    var startTime by remember { mutableStateOf(event?.startTime ?: "12:00") }
    var endTime by remember { mutableStateOf(event?.endTime ?: "13:00") }
    var selectedColor by remember { mutableStateOf(Color(event?.colorArgb ?: 0xFF2196F3.toInt())) }
    var recType by remember { mutableStateOf(event?.recurrenceType ?: 0) }
    val customDays = remember { mutableStateListOf(*(event?.customDaysOfWeek?.toTypedArray() ?: emptyArray())) }
    var hasNotification by remember { mutableStateOf(event?.hasNotification ?: false) }

    val days = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    val colors = listOf(Color(0xFF2196F3), Color(0xFFF44336), Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFF9C27B0), Color(0xFF00BCD4), Color(0xFFE91E63))

    fun pickTime(initial: String, onRes: (String) -> Unit) {
        val p = initial.split(":")
        TimePickerDialog(ctx, { _, h, m -> onRes(String.format(Locale.getDefault(), "%02d:%02d", h, m)) }, p[0].toInt(), p[1].toInt(), true).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (event == null) "Новое событие" else "Изменить событие") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth()) }
                
                // Выбор типа: Только Событие и Перерыв
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(selected = type == 0, onClick = { type = 0 }, label = { Text("Событие") })
                        FilterChip(selected = type == 1, onClick = { type = 1 }, label = { Text("Перерыв") })
                    }
                }

                // ВЫБОР ВРЕМЕНИ ВОЗВРАЩЕН
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { pickTime(startTime) { startTime = it } }) {
                            Text("Начало: $startTime", fontSize = 16.sp)
                        }
                        TextButton(onClick = { pickTime(endTime) { endTime = it } }) {
                            Text("Конец: $endTime", fontSize = 16.sp)
                        }
                    }
                }

                if (type == 0) {
                    item {
                        Text("Цвет метки:", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            colors.forEach { c ->
                                Box(modifier = Modifier.size(32.dp).clip(CircleShape).background(c).border(if (selectedColor == c) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).clickable { selectedColor = c })
                            }
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp).clip(CircleShape).background(Color.LightGray).clickable { 
                                selectedColor = Color(Random.nextInt(256), Random.nextInt(256), Random.nextInt(256))
                            }) { Icon(Icons.Default.Refresh, "Случайный цвет", tint = Color.White, modifier = Modifier.size(20.dp)) }
                        }
                    }

                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { hasNotification = !hasNotification }) {
                            Checkbox(checked = hasNotification, onCheckedChange = { hasNotification = it })
                            Text("Включить уведомление")
                            Icon(Icons.Default.Notifications, null, tint = if (hasNotification) MaterialTheme.colorScheme.primary else Color.Gray, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }

                item {
                    Text("Повторение:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    listOf("1 раз", "Каждый день", "Выбрать дни").forEachIndexed { i, txt ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { recType = i }) {
                            RadioButton(selected = recType == i, onClick = { recType = i })
                            Text(txt)
                        }
                    }
                }
                
                if (recType == 2) {
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            days.forEachIndexed { i, d ->
                                val dayNum = i + 1
                                val isSel = customDays.contains(dayNum)
                                FilterChip(selected = isSel, onClick = { if (isSel) customDays.remove(dayNum) else customDays.add(dayNum) }, label = { Text(d) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { 
            Button(onClick = { 
                if (title.isNotBlank()) onSave(DiaryEvent(id = event?.id ?: UUID.randomUUID().toString(), title = title, date = date.toString(), type = type, startTime = startTime, endTime = endTime, colorArgb = selectedColor.toArgb(), recurrenceType = recType, customDaysOfWeek = customDays.toList(), hasNotification = hasNotification)) 
            }) { Text("Сохранить") } 
        },
        dismissButton = { 
            Row {
                if (event != null) {
                    TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Удалить") }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") } 
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MonthCalendarView(currentDate: LocalDate, allEvents: List<DiaryEvent>, onDaySelected: (LocalDate) -> Unit, onBackClick: () -> Unit) {
    val pageCount = 1200
    val initialPage = pageCount / 2
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pageCount })
    
    LaunchedEffect(Unit) {
        val diff = ChronoUnit.MONTHS.between(YearMonth.now(), YearMonth.from(currentDate)).toInt()
        pagerState.scrollToPage(initialPage + diff)
    }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { 
                    val ym = YearMonth.now().plusMonths((pagerState.currentPage - initialPage).toLong())
                    Text(ym.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru"))).replaceFirstChar { it.uppercase() }) 
                }, 
                navigationIcon = { IconButton(onClick = onBackClick) { Icon(Icons.Default.ArrowBack, "Назад") } }
            ) 
        }
    ) { p ->
        HorizontalPager(state = pagerState, modifier = Modifier.padding(p).fillMaxSize()) { page ->
            val ym = YearMonth.now().plusMonths((page - initialPage).toLong())
            LazyVerticalGrid(columns = GridCells.Fixed(7), modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items((1..ym.lengthOfMonth()).toList()) { d ->
                    val date = ym.atDay(d)
                    val isSel = date == currentDate
                    // Игнорируем старые пропуски для точек
                    val hasEvents = allEvents.any { it.occursOn(date) && it.type != 2 }

                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onDaySelected(date) }) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.aspectRatio(1f).clip(CircleShape).background(when { isSel -> MaterialTheme.colorScheme.primary; date == LocalDate.now() -> MaterialTheme.colorScheme.secondaryContainer; else -> Color.Transparent })) {
                            Text(d.toString(), color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        }
                        if (hasEvents) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        } else {
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(isDark: Boolean, onThemeChange: (Boolean) -> Unit, defaultColor: Color, onColorChange: (Color) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Настройки") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") } }) }) { p ->
        Column(modifier = Modifier.padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Темная тема", fontSize = 18.sp)
                Switch(checked = isDark, onCheckedChange = onThemeChange)
            }
            HorizontalDivider()
            Text("Цвет карточек по умолчанию:", fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(Color(0xFFF0F0F0), Color(0xFFFFEBEE), Color(0xFFE8F5E9), Color(0xFFE3F2FD)).forEach { c ->
                    Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(c).border(if (defaultColor == c) 2.dp else 0.dp, Color.Gray, CircleShape).clickable { onColorChange(c) })
                }
            }
        }
    }
}