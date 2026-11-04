package uz.nexus.crm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Green = Color(0xFF087C42)
private val LightGreen = Color(0xFFE8F5EC)
private const val API_URL = "https://nexus-crm-backend-g8or.onrender.com"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { NexusApp() } }
    }
}

private suspend fun api(
    method: String,
    path: String,
    token: String? = null,
    body: JSONObject? = null
): String = withContext(Dispatchers.IO) {
    val c = URL(API_URL + path).openConnection() as HttpURLConnection
    try {
        c.requestMethod = method
        c.connectTimeout = 30000
        c.readTimeout = 60000
        c.setRequestProperty("Accept", "application/json")
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val detail = try { JSONObject(response).optString("detail", "Server xatosi: " + code) }
            catch (_: Exception) { "Server xatosi: " + code }
            throw Exception(detail)
        }
        response
    } finally { c.disconnect() }
}

private fun JSONObject.text(key: String): String {
    val v = optString(key, "")
    return if (v == "null") "" else v
}

private data class Session(
    val token: String,
    val username: String,
    val fullName: String,
    val role: String
)

@Composable
fun NexusApp() {
    var session by remember { mutableStateOf<Session?>(null) }
    var splash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1500)
        splash = false
    }
    if (splash) {
        Box(Modifier.fillMaxSize().background(Green), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NEXUS CRM PRO", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator(color = Color.White)
            }
        }
    } else {
        if (session == null) LoginScreen { session = it }
        else MainDashboard(session!!) { session = null }
    }
}

@Composable
fun LoginScreen(onLogin: (Session) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), color = LightGreen) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("NEXUS CRM PRO", color = Green, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Boshqaruv tizimiga kirish")
            Spacer(Modifier.height(25.dp))
            OutlinedTextField(username, { username = it; error = "" }, label = { Text("Login") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(password, { password = it; error = "" }, label = { Text("Parol") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (error.isNotBlank()) Text(error, color = Color.Red, modifier = Modifier.padding(8.dp))
            Button(
                onClick = {
                    if (username.isBlank() || password.isBlank()) error = "Login va parolni kiriting"
                    else {
                        loading = true
                        scope.launch {
                            try {
                                val response = JSONObject(api("POST", "/auth/login", body = JSONObject().put("username", username.trim()).put("password", password)))
                                val token = response.getString("access_token")
                                val me = JSONObject(api("GET", "/me", token))
                                onLogin(Session(token, me.text("username"), me.text("full_name"), me.text("role")))
                            } catch (e: Exception) {
                                error = e.message ?: "Ulanish xatosi"
                            } finally { loading = false }
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green)
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White)
                else Text("TIZIMGA KIRISH")
            }
        }
    }
}

@Composable
fun MainDashboard(session: Session, logout: () -> Unit) {
    var page by remember { mutableStateOf("home") }
    Column(Modifier.fillMaxSize().background(LightGreen)) {
        Row(
            Modifier.fillMaxWidth().background(Green).padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("NEXUS CRM PRO", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(session.fullName + " • " + session.role, color = Color.White, fontSize = 12.sp)
            }
            Text("Chiqish", color = Color.White, modifier = Modifier.clickable { logout() }.padding(8.dp))
        }
        if (page == "home") HomePage { page = it }
        else ApiPage(session, page) { page = "home" }
    }
}

@Composable
fun HomePage(open: (String) -> Unit) {
    val menu = listOf(
        "customers" to "Mijozlar",
        "applications" to "Arizalar",
        "employees" to "Xodimlar",
        "reports" to "Hisobotlar",
        "audit" to "Faoliyat tarixi"
    )
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Boshqaruv paneli", color = Green, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("Kerakli bo‘limni tanlang", color = Color.Gray)
        Spacer(Modifier.height(18.dp))
        menu.forEach { item ->
            Card(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { open(item.first) },
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (item.first) {
                            "customers" -> "👥"
                            "applications" -> "📋"
                            "employees" -> "👨‍💼"
                            "reports" -> "📊"
                            else -> "🕘"
                        },
                        fontSize = 25.sp
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(item.second, Modifier.weight(1f), color = Green, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text("›", color = Green, fontSize = 26.sp)
                }
            }
        }
    }
}

@Composable
fun ApiPage(session: Session, page: String, back: () -> Unit) {
    val title = when (page) {
        "customers" -> "Mijozlar"
        "applications" -> "Arizalar"
        "employees" -> "Xodimlar"
        "reports" -> "Hisobotlar"
        else -> "Faoliyat tarixi"
    }
    var raw by remember(page) { mutableStateOf("") }
    var loading by remember(page) { mutableStateOf(true) }
    var error by remember(page) { mutableStateOf("") }
    var refresh by remember(page) { mutableIntStateOf(0) }
    var dialog by remember(page) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(page, refresh) {
        loading = true
        error = ""
        try {
            raw = when (page) {
                "customers" -> api("GET", "/customers", session.token)
                "applications" -> api("GET", "/applications", session.token)
                "employees" -> api("GET", "/users", session.token)
                "audit" -> api("GET", "/audit", session.token)
                else -> JSONObject()
                    .put("customers", JSONArray(api("GET", "/customers", session.token)))
                    .put("applications", JSONArray(api("GET", "/applications", session.token))).toString()
            }
        } catch (e: Exception) { error = e.message ?: "Ma’lumot yuklanmadi" }
        finally { loading = false }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Orqaga", color = Green, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { back() }.padding(8.dp))
            Text(title, Modifier.weight(1f), color = Green, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("↻", color = Green, fontSize = 24.sp, modifier = Modifier.clickable { refresh++ }.padding(8.dp))
        }

        if (page == "employees" && session.role != "admin") {
            MessageCard("Xodimlar bo‘limi faqat administrator uchun.")
        } else if (page == "audit" && session.role != "admin") {
            MessageCard("Faoliyat tarixi faqat administrator uchun.")
        } else if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Green) }
        } else if (error.isNotBlank()) {
            Column {
                MessageCard(error)
                Button(onClick = { refresh++ }, colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Qayta urinish") }
            }
        } else {
            when (page) {
                "customers" -> {
                    ActionButton("＋ Yangi mijoz") { dialog = true }
                    CustomerRows(raw)
                }
                "applications" -> {
                    ActionButton("＋ Yangi ariza") { dialog = true }
                    ApplicationRows(raw, session) { refresh++ }
                }
                "employees" -> {
                    ActionButton("＋ Xodim qo‘shish") { dialog = true }
                    EmployeeRows(raw)
                }
                "audit" -> AuditRows(raw)
                "reports" -> ReportRows(raw)
            }
        }
    }

    if (dialog) {
        when (page) {
            "customers" -> CustomerForm(
                close = { dialog = false },
                save = { name, phone, address ->
                    scope.launch {
                        try {
                            api("POST", "/customers", session.token, JSONObject().put("full_name", name).put("phone", phone).put("address", if (address.isBlank()) JSONObject.NULL else address))
                            dialog = false
                            refresh++
                        } catch (e: Exception) { error = e.message ?: "Saqlash xatosi"; dialog = false }
                    }
                }
            )
            "applications" -> ApplicationForm(
                close = { dialog = false },
                save = { customerId, titleText, desc ->
                    scope.launch {
                        try {
                            api("POST", "/applications", session.token, JSONObject().put("customer_id", customerId).put("title", titleText).put("description", if (desc.isBlank()) JSONObject.NULL else desc))
                            dialog = false
                            refresh++
                        } catch (e: Exception) { error = e.message ?: "Saqlash xatosi"; dialog = false }
                    }
                }
            )
            "employees" -> EmployeeForm(
                close = { dialog = false },
                save = { name, login, password, role ->
                    scope.launch {
                        try {
                            api("POST", "/users", session.token, JSONObject().put("full_name", name).put("username", login).put("password", password).put("role", role))
                            dialog = false
                            refresh++
                        } catch (e: Exception) { error = e.message ?: "Saqlash xatosi"; dialog = false }
                    }
                }
            )
        }
    }
}

@Composable
fun ActionButton(label: String, action: () -> Unit) {
    Button(onClick = action, modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
        Text(label)
    }
}

@Composable
fun MessageCard(message: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Text(message, Modifier.padding(16.dp), color = Color.DarkGray)
    }
}

@Composable
fun CustomerRows(raw: String) {
    val arr = remember(raw) { JSONArray(raw) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (arr.length() == 0) item { MessageCard("Hozircha mijozlar yo‘q.") }
        items((0 until arr.length()).toList()) { i ->
            val o = arr.getJSONObject(i)
            MessageCard("#" + o.optInt("id") + "  " + o.text("full_name") + "\nTelefon: " + o.text("phone") + "\nManzil: " + o.text("address"))
        }
    }
}

@Composable
fun ApplicationRows(raw: String, session: Session, changed: () -> Unit) {
    val arr = remember(raw) { JSONArray(raw) }
    val scope = rememberCoroutineScope()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (arr.length() == 0) item { MessageCard("Hozircha arizalar yo‘q.") }
        items((0 until arr.length()).toList()) { i ->
            val o = arr.getJSONObject(i)
            val id = o.optInt("id")
            val status = o.text("status")
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(13.dp)) {
                    Text("#" + id + " • " + o.text("title"), color = Green, fontWeight = FontWeight.Bold)
                    Text("Mijoz ID: " + o.optInt("customer_id"))
                    Text("Holat: " + status)
                    Text(o.text("description"), color = Color.Gray)
                    if (status == "pending" && (session.role == "manager" || session.role == "admin")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    try {
                                        api("PATCH", "/applications/" + id + "/review", session.token, JSONObject().put("status", "approved"))
                                        changed()
                                    } catch (_: Exception) { }
                                }
                            }, colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Tasdiqlash") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    try {
                                        api("PATCH", "/applications/" + id + "/review", session.token, JSONObject().put("status", "rejected"))
                                        changed()
                                    } catch (_: Exception) { }
                                }
                            }) { Text("Rad etish") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmployeeRows(raw: String) {
    val arr = remember(raw) { JSONArray(raw) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (arr.length() == 0) item { MessageCard("Xodimlar topilmadi.") }
        items((0 until arr.length()).toList()) { i ->
            val o = arr.getJSONObject(i)
            MessageCard(o.text("full_name") + "\nLogin: " + o.text("username") + "\nRol: " + o.text("role"))
        }
    }
}

@Composable
fun AuditRows(raw: String) {
    val arr = remember(raw) { JSONArray(raw) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (arr.length() == 0) item { MessageCard("Audit yozuvlari yo‘q.") }
        items((0 until arr.length()).toList()) { i ->
            val o = arr.getJSONObject(i)
            MessageCard("#" + o.optInt("id") + " • " + o.text("action") + "\nXodim ID: " + o.optInt("user_id") + "\n" + o.text("details") + "\n" + o.text("created_at"))
        }
    }
}

@Composable
fun ReportRows(raw: String) {
    val obj = remember(raw) { JSONObject(raw) }
    val customers = obj.optJSONArray("customers") ?: JSONArray()
    val apps = obj.optJSONArray("applications") ?: JSONArray()
    var pending = 0
    var approved = 0
    var rejected = 0
    for (i in 0 until apps.length()) {
        when (apps.getJSONObject(i).text("status")) {
            "pending" -> pending++
            "approved" -> approved++
            "rejected" -> rejected++
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ReportTile("Jami mijozlar", customers.length())
        ReportTile("Jami arizalar", apps.length())
        ReportTile("Jarayonda", pending)
        ReportTile("Tasdiqlangan", approved)
        ReportTile("Rad etilgan", rejected)
    }
}

@Composable
fun ReportTile(label: String, value: Int) {
    Card(Modifier.fillMaxWidth().padding(bottom = 9.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.fillMaxWidth().padding(17.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(value.toString(), color = Green, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }
}

@Composable
fun CustomerForm(close: () -> Unit, save: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Yangi mijoz") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("F.I.Sh.") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("Telefon") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
                OutlinedTextField(address, { address = it }, label = { Text("Manzil") })
            }
        },
        confirmButton = { TextButton(onClick = { if (name.trim().length >= 2 && phone.trim().length >= 7) save(name.trim(), phone.trim(), address.trim()) }) { Text("Saqlash") } },
        dismissButton = { TextButton(onClick = close) { Text("Bekor qilish") } }
    )
}

@Composable
fun ApplicationForm(close: () -> Unit, save: (Int, String, String) -> Unit) {
    var customerId by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Yangi ariza") },
        text = {
            Column {
                OutlinedTextField(customerId, { customerId = it.filter(Char::isDigit) }, label = { Text("Mijoz ID raqami") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                OutlinedTextField(title, { title = it }, label = { Text("Ariza nomi") }, singleLine = true)
                OutlinedTextField(desc, { desc = it }, label = { Text("Izoh") })
            }
        },
        confirmButton = { TextButton(onClick = { val id = customerId.toIntOrNull(); if (id != null && title.trim().length >= 2) save(id, title.trim(), desc.trim()) }) { Text("Yuborish") } },
        dismissButton = { TextButton(onClick = close) { Text("Bekor qilish") } }
    )
}

@Composable
fun EmployeeForm(close: () -> Unit, save: (String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("operator") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Yangi xodim") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("F.I.Sh.") }, singleLine = true)
                OutlinedTextField(login, { login = it }, label = { Text("Login") }, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("Parol, kamida 8 belgi") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Text("Lavozim")
                listOf("operator", "manager", "admin").forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = role == r, onClick = { role = r })
                        Text(r)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.trim().length >= 2 && login.trim().length >= 3 && password.length >= 8) save(name.trim(), login.trim(), password, role) }) { Text("Saqlash") } },
        dismissButton = { TextButton(onClick = close) { Text("Bekor qilish") } }
    )
}
