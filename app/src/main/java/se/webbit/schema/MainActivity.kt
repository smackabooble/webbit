package se.webbit.schema

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.*
import java.time.format.DateTimeFormatter

val ZONE: ZoneId = ZoneId.of("Europe/Stockholm")
const val CAL_SCOPE = "https://www.googleapis.com/auth/calendar.readonly"

data class Lesson(val start: LocalTime, val end: LocalTime, val name: String)

private fun l(s: String, e: String, n: String) = Lesson(LocalTime.parse(s), LocalTime.parse(e), n)

val SCHEMA: Map<DayOfWeek, List<Lesson>> = mapOf(
    DayOfWeek.MONDAY to listOf(
        l("08:10", "09:30", "Textil Fyrgrupper Makemake"), l("09:40", "10:15", "NO Mars"),
        l("11:05", "12:00", "Idrott Hjällsnäs"), l("12:00", "12:30", "Lunch"),
        l("12:30", "13:00", "Levande lärande Venus"), l("13:10", "14:30", "NO LABB Fyrgrupper Neptunus")),
    DayOfWeek.TUESDAY to listOf(
        l("08:10", "09:20", "Matte Mars (ej dator)"), l("09:35", "10:55", "Bild Fyrgrupper Saturnus (ej dator)"),
        l("11:05", "11:55", "Svenska Venus"), l("12:00", "12:25", "Lunch"),
        l("12:45", "13:55", "Tyska"), l("14:20", "15:15", "SO Jupiter")),
    DayOfWeek.WEDNESDAY to listOf(
        l("08:10", "08:50", "NO Mars"), l("09:00", "09:40", "Idrott TEORI Venus"),
        l("09:55", "10:45", "Teknik Neptunus"), l("11:05", "11:55", "SO Jupiter"),
        l("12:00", "12:45", "Lunch"), l("12:45", "14:05", "Träslöjd Fyrgrupper Ceres"),
        l("14:15", "14:55", "Matte Mars (ej dator)")),
    DayOfWeek.THURSDAY to listOf(
        l("08:10", "09:00", "Engelska Jupiter"), l("09:45", "10:55", "Tyska"),
        l("11:20", "11:55", "Svenska Venus"), l("12:00", "12:45", "Lunch"),
        l("12:45", "13:40", "SO Jupiter"), l("13:55", "14:55", "Idrott Hjällsnäs")),
    DayOfWeek.FRIDAY to listOf(
        l("08:10", "09:30", "Svenska Venus"), l("09:40", "10:55", "Matte Tellus"),
        l("11:00", "11:55", "SO Jupiter"), l("12:00", "12:20", "Lunch"),
        l("13:00", "13:55", "Matte Tellus"), l("14:05", "14:50", "Engelska Jupiter")),
)
val DAY_NAMES = mapOf(
    DayOfWeek.MONDAY to "Mån", DayOfWeek.TUESDAY to "Tis", DayOfWeek.WEDNESDAY to "Ons",
    DayOfWeek.THURSDAY to "Tor", DayOfWeek.FRIDAY to "Fre")

fun statusNow(now: LocalDateTime): String {
    val lessons = SCHEMA[now.dayOfWeek] ?: return "Helg"
    val t = now.toLocalTime()
    if (lessons.isEmpty() || t < lessons.first().start) return "Skolan har inte börjat"
    if (t >= lessons.last().end) return "Skoldagen är slut"
    lessons.firstOrNull { t >= it.start && t < it.end }?.let { return "${it.name} (till ${it.end})" }
    return "Rast"
}

fun nextLesson(now: LocalDateTime): String {
    var date = now.toLocalDate()
    repeat(8) { i ->
        val lessons = (SCHEMA[date.dayOfWeek] ?: emptyList()).filter { it.name != "Lunch" }
        val found = lessons.firstOrNull { i > 0 || it.start > now.toLocalTime() }
        if (found != null) return "${DAY_NAMES[date.dayOfWeek]} ${found.start}-${found.end} ${found.name}"
        date = date.plusDays(1)
    }
    return "-"
}

data class CalEvent(val title: String, val whenText: String)

suspend fun fetchEvents(token: String): List<CalEvent> = withContext(Dispatchers.IO) {
    val now = ZonedDateTime.now(ZONE)
    fun q(z: ZonedDateTime) = URLEncoder.encode(z.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), "UTF-8")
    val url = "https://www.googleapis.com/calendar/v3/calendars/primary/events" +
        "?singleEvents=true&orderBy=startTime&maxResults=50&timeMin=${q(now)}&timeMax=${q(now.plusDays(14))}"
    val c = URL(url).openConnection() as HttpURLConnection
    c.setRequestProperty("Authorization", "Bearer $token")
    if (c.responseCode != 200) error("Kalender-fel ${c.responseCode}")
    val items = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("items")
    val out = mutableListOf<CalEvent>()
    if (items != null) for (i in 0 until items.length()) {
        val e = items.getJSONObject(i)
        val s = e.getJSONObject("start")
        val whenText = if (s.has("dateTime"))
            OffsetDateTime.parse(s.getString("dateTime")).atZoneSameInstant(ZONE)
                .format(DateTimeFormatter.ofPattern("EEE d/M HH:mm"))
        else s.optString("date")
        out += CalEvent(e.optString("summary", "(utan titel)"), whenText)
    }
    out
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf<List<CalEvent>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val now = remember { LocalDateTime.now(ZONE) }

    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail().requestScopes(Scope(CAL_SCOPE)).build()
    }
    val client = remember { GoogleSignIn.getClient(ctx, gso) }

    fun load() = scope.launch {
        try {
            val acc = GoogleSignIn.getLastSignedInAccount(ctx)?.account ?: return@launch
            val token = withContext(Dispatchers.IO) { GoogleAuthUtil.getToken(ctx, acc, "oauth2:$CAL_SCOPE") }
            events = fetchEvents(token); error = null
        } catch (e: Exception) { error = e.message ?: e.toString() }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { load() }
    LaunchedEffect(Unit) { load() }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Nu: ${statusNow(now)}", style = MaterialTheme.typography.titleMedium)
            Text("Nästa: ${nextLesson(now)}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text("Google Kalender", style = MaterialTheme.typography.titleLarge)
            if (events == null) Button(onClick = { launcher.launch(client.signInIntent) }) { Text("Logga in med Google") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        events?.let { ev ->
            if (ev.isEmpty()) item { Text("Inga events de närmaste 14 dagarna") }
            items(ev) { Text("${it.whenText}  ${it.title}") }
        }
        item { Spacer(Modifier.height(12.dp)); Text("Schema", style = MaterialTheme.typography.titleLarge) }
        SCHEMA.forEach { (day, lessons) ->
            item { Text(DAY_NAMES[day]!!, style = MaterialTheme.typography.titleMedium) }
            items(lessons) { Text("${it.start}-${it.end}  ${it.name}") }
        }
    }
}
