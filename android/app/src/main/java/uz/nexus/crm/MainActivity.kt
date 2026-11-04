package uz.nexus.crm

import android.os.Bundle
import android.app.DownloadManager
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.border
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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

private fun normalizePassportSeries(value: String): String =
    value.uppercase()
        .map { ch ->
            when (ch) {
                'А' -> 'A'
                'В' -> 'B'
                'Е' -> 'E'
                'К' -> 'K'
                'М' -> 'M'
                'Н' -> 'H'
                'О' -> 'O'
                'Р' -> 'P'
                'С' -> 'C'
                'Т' -> 'T'
                'Х' -> 'X'
                'У' -> 'Y'
                else -> ch
            }
        }
        .filter { it in 'A'..'Z' }
        .take(2)
        .joinToString("")

private val darkMode = mutableStateOf(false)
private val Green: Color get() = if (darkMode.value) Color(0xFF8AB4FF) else Color(0xFF0754C7)
private val LightGreen: Color get() = if (darkMode.value) Color(0xFF101827) else Color(0xFFF1F6FF)
private val CardSurface: Color get() = if (darkMode.value) Color(0xFF1B2638) else Color.White
private val PrimaryText: Color get() = if (darkMode.value) Color(0xFFE8EEF8) else Color(0xFF10264D)
private val SecondaryText: Color get() = if (darkMode.value) Color(0xFFB7C3D5) else Color(0xFF657594)
private const val API_URL = "https://nexus-crm-backend-g8or.onrender.com"

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkMode.value = getSharedPreferences("nexus_settings", MODE_PRIVATE).getBoolean("dark_mode", false)
        setContent {
            val dark = darkMode.value
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme(
                    primary = Color(0xFF8AB4FF),
                    background = Color(0xFF101827),
                    surface = Color(0xFF1B2638),
                    onSurface = Color(0xFFE8EEF8)
                ) else lightColorScheme(
                    primary = Color(0xFF0754C7),
                    background = Color(0xFFF1F6FF),
                    surface = Color.White,
                    onSurface = Color(0xFF10264D)
                )
            ) { NexusApp() }
        }
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

data class Session(
    val token: String,
    val username: String,
    val fullName: String,
    val role: String
)

internal fun secureSessionPrefs(context: Context): android.content.SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        "nexus_secure_session",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}

@Composable
fun NexusApp() {
    val context = LocalContext.current
    var session by remember { mutableStateOf<Session?>(null) }
    var splash by remember { mutableStateOf(true) }
    var restoring by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            // Keep the saved token for biometric login, but do not bypass the login screen.
            secureSessionPrefs(context).getString("access_token", null)
        } catch (_: Exception) {
            try { secureSessionPrefs(context).edit().clear().apply() } catch (_: Exception) {}
        } finally {
            restoring = false
        }
        kotlinx.coroutines.delay(1200)
        splash = false
    }

    if (splash || restoring) {
        Box(Modifier.fillMaxSize().background(Green), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Mrpolatjan CRM", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator(color = Color.White)
            }
        }
    } else if (session == null) {
        LoginScreen(
            onLogin = { loggedIn ->
                try {
                    secureSessionPrefs(context).edit()
                        .putString("access_token", loggedIn.token)
                        .apply()
                } catch (_: Exception) {
                    Toast.makeText(context, "Sessiyani saqlab bo‘lmadi", Toast.LENGTH_SHORT).show()
                }
                session = loggedIn
            }
        )
    } else {
        MainDashboard(session!!, logout = { session = null })
    }
}

@Composable
fun LoginScreen(onLogin: (Session) -> Unit) {
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var hasSavedLogin by remember { mutableStateOf(false) }
    var hasFaceTemplate by remember { mutableStateOf(FaceTemplateStore.exists(context)) }
    var showFaceCamera by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val faceCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showFaceCamera = true
        else error = "Yuz orqali kirish uchun kameraga ruxsat bering."
    }

    LaunchedEffect(Unit) {
        hasSavedLogin = try {
            !secureSessionPrefs(context).getString("access_token", null).isNullOrBlank()
        } catch (_: Exception) { false }
    }

    Surface(Modifier.fillMaxSize(), color = LightGreen) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Box(Modifier.size(76.dp).background(Green, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                        Text("M", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Mrpolatjan", color = PrimaryText, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold)
                        Text("CRM", color = Green, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.height(20.dp)) {
                            listOf(8, 12, 16, 20).forEach { h ->
                                Box(Modifier.width(7.dp).height(h.dp).background(Green, RoundedCornerShape(3.dp)))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("CRM – Boshqaruv tizimi", color = SecondaryText, fontSize = 15.sp)
                Spacer(Modifier.height(28.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = CardSurface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 5.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("Tizimga kirish", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Login-parol yoki yuz orqali kiring", color = SecondaryText, fontSize = 12.sp)
                        Spacer(Modifier.height(20.dp))
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it; error = "" },
                            label = { Text("Login") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; error = "" },
                            label = { Text("Parol") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp)
                        )
                        if (error.isNotBlank()) {
                            Text(error, color = Color.Red, modifier = Modifier.padding(top = 10.dp), fontSize = 13.sp)
                        }
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
                            modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(54.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Green)
                        ) {
                            if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White)
                            else Text("LOGIN VA PAROL BILAN KIRISH", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                val savedToken = try {
                                    secureSessionPrefs(context).getString("access_token", null)
                                } catch (_: Exception) { null }
                                if (savedToken.isNullOrBlank()) {
                                    error = "Avval login va parol bilan bir marta kiring."
                                } else if (!FaceTemplateStore.exists(context)) {
                                    hasFaceTemplate = false
                                    error = "Avval menyudan yuzingizni ro‘yxatdan o‘tkazing."
                                } else if (androidx.core.content.ContextCompat.checkSelfPermission(
                                        context, android.Manifest.permission.CAMERA
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    error = ""
                                    showFaceCamera = true
                                } else {
                                    faceCameraPermission.launch(android.Manifest.permission.CAMERA)
                                }
                            },
                            enabled = !loading && hasSavedLogin && hasFaceTemplate,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF153B70))
                        ) {
                            Text("◉  KAMERA ORQALI YUZ BILAN KIRISH", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        if (!hasSavedLogin) {
                            Text("Yuz orqali kirish birinchi login-parol kirishidan keyin faollashadi.", color = SecondaryText, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text("Xavfsiz. Tez. Ishonchli.", color = Green, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("© 2026 Mrpolatjan CRM", color = SecondaryText, fontSize = 11.sp)
            }
        }
    }
    if (showFaceCamera) {
        Dialog(
            onDismissRequest = { if (!loading) showFaceCamera = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            CameraCaptureScreen(
                requireLiveness = true,
                onClose = { if (!loading) showFaceCamera = false },
                onCaptured = { bitmap ->
                    showFaceCamera = false
                    scope.launch {
                        loading = true
                        var croppedFace: Bitmap? = null
                        try {
                            val savedToken = secureSessionPrefs(context).getString("access_token", null)
                                ?: throw Exception("Saqlangan sessiya topilmadi. Login-parol bilan kiring.")
                            val savedEmbedding = FaceTemplateStore.read(context)
                                ?: throw Exception("Yuz shabloni topilmadi. Qayta ro‘yxatdan o‘tkazing.")
                            croppedFace = FaceImageProcessor.detectAndCrop(context, bitmap)
                            val currentEmbedding = withContext(Dispatchers.Default) {
                                FaceRecognitionEngine.create(context).use { it.createEmbedding(croppedFace!!) }
                            }
                            val similarity = FaceRecognitionEngine.create(context).use {
                                it.cosineSimilarity(savedEmbedding, currentEmbedding)
                            }
                            // Experimental threshold; must be calibrated on real devices before production use.
                            if (similarity < 0.72f) {
                                throw Exception("Yuz mos kelmadi. Yaxshiroq yorug‘likda qayta urinib ko‘ring yoki login-parol bilan kiring.")
                            }
                            val me = JSONObject(api("GET", "/me", savedToken))
                            onLogin(Session(savedToken, me.text("username"), me.text("full_name"), me.text("role")))
                        } catch (e: Exception) {
                            error = e.message ?: "Yuz orqali kirishda xatolik."
                        } finally {
                            croppedFace?.recycle()
                            bitmap.recycle()
                            loading = false
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun MainDashboard(session: Session, logout: () -> Unit) {
    val context = LocalContext.current
    var page by remember { mutableStateOf("home") }
    var faceMessage by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var selectedContractCode by remember { mutableStateOf("") }
    var selectedStockAmount by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(LightGreen)) {
        Row(
            Modifier.fillMaxWidth().background(Green).padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Text("☰", color = Color.White, fontSize = 27.sp,
                    modifier = Modifier.clickable { menuExpanded = true }.padding(8.dp))
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Sozlamalar") },
                        onClick = { menuExpanded = false; page = "settings" }
                    )
                    DropdownMenuItem(
                        text = { Text("◉ Yuz orqali kirishni sozlash") },
                        onClick = { menuExpanded = false; page = "face_settings" }
                    )
                    DropdownMenuItem(
                        text = { Text(if (darkMode.value) "☀ Kunduzgi rejim" else "☾ Qorong‘i rejim") },
                        onClick = {
                            darkMode.value = !darkMode.value
                            context.getSharedPreferences("nexus_settings", Context.MODE_PRIVATE)
                                .edit().putBoolean("dark_mode", darkMode.value).apply()
                            menuExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Bosh sahifa") },
                        onClick = { menuExpanded = false; page = "home" }
                    )
                    DropdownMenuItem(
                        text = { Text("Chiqish") },
                        onClick = { menuExpanded = false; logout() }
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text("NEXUS CRM", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text(session.fullName + " • " + session.role, color = Color.White, fontSize = 11.sp)
            }
        }
        when (page) {
            "home" -> HomePage { page = it }
            "updates" -> UpdatePage { page = "home" }
            "settings" -> SettingsPage(
                dark = darkMode.value,
                onDarkChange = {
                    darkMode.value = it
                    context.getSharedPreferences("nexus_settings", Context.MODE_PRIVATE)
                        .edit().putBoolean("dark_mode", it).apply()
                },
                back = { page = "home" }
            )
            "workflow" -> SorovnomaPage(session = session, back = { page = "home" })
            "face_settings" -> FaceLoginSettingsPage(
                message = faceMessage,
                back = { page = "home" }
            )
            else -> ApiPage(
                session = session,
                page = page,
                back = { page = "home" },
                selectedContractCode = selectedContractCode,
                onOpenStock = { code -> selectedContractCode = code; page = "stock" },
                onOpenInvoice = { code, amount -> selectedContractCode = code; selectedStockAmount = amount; page = "invoices" },
                selectedStockAmount = selectedStockAmount,
                onContractCreated = { page = "contracts" }
            )
        }
    }
}

@Composable
fun FaceLoginSettingsPage(
    message: String,
    back: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showCamera by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(message) }
    var enrolled by remember { mutableStateOf(FaceTemplateStore.exists(context)) }

    Column(
        Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("‹ Orqaga", color = Green, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { back() }.padding(8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Kamera orqali yuzni ro‘yxatdan o‘tkazish", color = Green,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Yuz tasviri telefonda qayta ishlanadi. Xom surat saqlanmaydi; yuz shabloni telefonning himoyalangan xotirasida saqlanadi.",
                    color = PrimaryText, fontSize = 13.sp
                )
                Text(
                    if (enrolled) "Holat: yuz shabloni saqlangan" else "Holat: yuz hali ro‘yxatdan o‘tmagan",
                    color = if (enrolled) Green else SecondaryText,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = { status = ""; showCamera = true },
                    enabled = !processing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Green)
                ) {
                    Text(if (enrolled) "YUZNI QAYTA RO‘YXATDAN O‘TKAZISH" else "YUZIMNI RO‘YXATDAN O‘TKAZISH")
                }
                OutlinedButton(
                    onClick = {
                        FaceTemplateStore.clear(context)
                        enrolled = false
                        status = "Yuz shabloni o‘chirildi."
                    },
                    enabled = enrolled && !processing,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("YUZ SHABLONINI O‘CHIRISH") }
                if (processing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Green)
                        Text("Yuz tekshirilmoqda…", color = SecondaryText)
                    }
                }
                if (status.isNotBlank()) Text(status, color = Green, fontSize = 13.sp)
                Text(
                    "Eslatma: kirishda kamera ko‘z yumib-ochish va boshni burish harakatlarini tekshiradi. Bu oddiy suratni qaytarishga qarshi qo‘shimcha tekshiruv, ammo professional anti-spoof himoyasi emas.",
                    color = SecondaryText, fontSize = 11.sp
                )
            }
        }
    }

    if (showCamera) {
        Dialog(
            onDismissRequest = { if (!processing) showCamera = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            CameraCaptureScreen(
                onClose = { if (!processing) showCamera = false },
                onCaptured = { bitmap ->
                    showCamera = false
                    scope.launch {
                        processing = true
                        try {
                            val face = FaceImageProcessor.detectAndCrop(context, bitmap)
                            val embedding = withContext(Dispatchers.Default) {
                                FaceRecognitionEngine.create(context).use { it.createEmbedding(face) }
                            }
                            FaceTemplateStore.save(context, embedding)
                            enrolled = true
                            status = "Yuzingiz muvaffaqiyatli ro‘yxatdan o‘tdi."
                        } catch (e: Exception) {
                            status = e.message ?: "Yuzni ro‘yxatdan o‘tkazishda xatolik."
                        } finally {
                            processing = false
                            bitmap.recycle()
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun SettingsPage(dark: Boolean, onDarkChange: (Boolean) -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Orqaga", color = Green, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { back() }.padding(8.dp))
            Text("Sozlamalar", color = Green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tashqi ko‘rinish", color = PrimaryText, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("Ilova rang rejimini tanlang", color = SecondaryText, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onDarkChange(false) }, modifier = Modifier.weight(1f)) {
                        Text("☀ Kunduzgi")
                    }
                    Button(onClick = { onDarkChange(true) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                        Text("☾ Qorong‘i")
                    }
                }
                Text(if (dark) "Hozir qorong‘i rejim yoqilgan." else "Hozir kunduzgi rejim yoqilgan.",
                    color = SecondaryText, fontSize = 12.sp)
            }
        }
    }
}


@Composable
fun UpdatePage(back: () -> Unit) {
    val context = LocalContext.current
    var checking by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var latestVersion by remember { mutableStateOf("") }
    var downloadUrl by remember { mutableStateOf("") }
    var currentVersion by remember { mutableStateOf("1.0.0") }

    LaunchedEffect(Unit) {
        try {
            currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
            val release = withContext(Dispatchers.IO) {
                val connection = URL("https://api.github.com/repos/Mrpolatjan20/NEXUS-CRM-PRO12/releases/latest").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15000
                    connection.readTimeout = 20000
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    connection.setRequestProperty("User-Agent", "NEXUS-CRM-Android")
                    if (connection.responseCode !in 200..299) throw Exception("Yangilanish topilmadi")
                    JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                } finally { connection.disconnect() }
            }
            latestVersion = release.optString("tag_name", "").removePrefix("v")
            val assets = release.optJSONArray("assets") ?: JSONArray()
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name") == "Mrpolatjan-CRM.apk") {
                    downloadUrl = asset.optString("browser_download_url", "")
                    break
                }
            }
            if (latestVersion.isBlank() || downloadUrl.isBlank()) error = "Hozircha Release APK chiqarilmagan."
        } catch (e: Exception) {
            error = e.message ?: "Yangilanishni tekshirishda xatolik."
        } finally {
            checking = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Orqaga", color = Green, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { back() }.padding(8.dp))
            Text("Ilovani yangilash", color = Green, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("NEXUS CRM", color = Green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("O‘rnatilgan versiya: $currentVersion", color = PrimaryText)
                if (checking) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Green)
                        Spacer(Modifier.width(10.dp))
                        Text("Yangilanish tekshirilmoqda…")
                    }
                } else if (latestVersion.isNotBlank() && downloadUrl.isNotBlank()) {
                    Text("Oxirgi versiya: $latestVersion", color = PrimaryText)
                    if (latestVersion != currentVersion) {
                        Text("Yangi versiya mavjud.", color = Green, fontWeight = FontWeight.SemiBold)
                        Button(
                            onClick = {
                                try {
                                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                    val request = DownloadManager.Request(Uri.parse(downloadUrl))
                                        .setTitle("NEXUS CRM yangilanishi")
                                        .setDescription("APK yuklab olinmoqda. Yuklangach bildirishnomani bosing.")
                                        .setMimeType("application/vnd.android.package-archive")
                                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "Mrpolatjan-CRM.apk")
                                    manager.enqueue(request)
                                    Toast.makeText(context, "APK yuklanmoqda. Tugagach bildirishnomani bosing.", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    error = e.message ?: "Yuklab olish boshlanmadi."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Green),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("YANGILANISHNI YUKLAB OLISH") }
                        Text("Yuklab olish tugagach Android o‘rnatish oynasini oching va tasdiqlang. Ilovani o‘chirish shart emas.", color = Color.Gray, fontSize = 12.sp)
                    } else {
                        Text("Ilova eng so‘nggi versiyada.", color = Green, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (error.isNotBlank()) Text(error, color = Color(0xFFB3261E), fontSize = 13.sp)
            }
        }
    }
}


@Composable
fun HomePage(open: (String) -> Unit) {
    val menu = listOf(
        "workflow" to "So‘rovnoma",
        "applications" to "Moliyalashtirish arizalari",
        "employees" to "Xodimlar",
        "reports" to "Hisobotlar",
        "audit" to "Faoliyat tarixi",
        "updates" to "Ilovani yangilash"
    )
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Bosh sahifa", color = Green, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("Mijoz va savdo jarayonlarini boshqarish", color = Color.Gray)
        Spacer(Modifier.height(18.dp))
        menu.forEach { item ->
            Card(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { open(item.first) },
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (item.first) {
                            "workflow" -> "📝"
                            "applications" -> "📋"
                            "employees" -> "👨‍💼"
                            "reports" -> "📊"
                            "updates" -> "⬇️"
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
fun SorovnomaPage(session: Session, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var fullName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var pinfl by remember { mutableStateOf("") }
    var passport by remember { mutableStateOf("") }
    var offer by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var customerId by remember { mutableIntStateOf(0) }
    var contractCode by remember { mutableStateOf("") }
    var contractStatus by remember { mutableStateOf("") }
    var stockAmount by remember { mutableStateOf("") }
    var stockStatus by remember { mutableStateOf("") }
    var supplier by remember { mutableStateOf("") }
    var invoiceAmount by remember { mutableStateOf("") }
    var invoiceStatus by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh, contractCode) {
        if (contractCode.isNotBlank()) {
            try {
                val contracts = JSONArray(api("GET", "/contracts", session.token))
                for (i in 0 until contracts.length()) {
                    val item = contracts.getJSONObject(i)
                    if (item.optString("contract_code") == contractCode) contractStatus = item.optString("status")
                }
                val stocks = JSONArray(api("GET", "/stock-outs", session.token))
                for (i in 0 until stocks.length()) {
                    val item = stocks.getJSONObject(i)
                    if (item.optString("contract_code") == contractCode) stockStatus = item.optString("status")
                }
                val invoices = JSONArray(api("GET", "/invoices", session.token))
                for (i in 0 until invoices.length()) {
                    val item = invoices.getJSONObject(i)
                    if (item.optString("contract_code") == contractCode) invoiceStatus = item.optString("status")
                }
            } catch (e: Exception) { message = e.message ?: "Holatlarni yuklashda xato" }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Orqaga", color = Green, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { back() }.padding(8.dp))
            Text("So‘rovnoma", color = Green, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("↻", color = Green, fontSize = 22.sp, modifier = Modifier.clickable { refresh++ }.padding(8.dp))
        }
        Text("Mijoz → Shartnoma → Chiqim → Faktura — hammasi bitta oynada.", color = SecondaryText, fontSize = 12.sp)

        Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. MIJOZ", color = Green, fontWeight = FontWeight.Bold)
                OutlinedTextField(fullName, { fullName = it }, label = { Text("F.I.Sh.") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("Telefon raqami") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(address, { address = it }, label = { Text("Manzil") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(passport, { passport = it.uppercase().take(9) }, label = { Text("Pasport (AA1234567)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(pinfl, { pinfl = it.filter(Char::isDigit).take(14) }, label = { Text("JSHSHIR (14 raqam)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = offer, onCheckedChange = { offer = it }); Text("Ommaviy oferta shartlariga roziman", color = PrimaryText, fontSize = 12.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = consent, onCheckedChange = { consent = it }); Text("Shaxsiy ma’lumotlarni qayta ishlashga roziman", color = PrimaryText, fontSize = 12.sp) }
                if (customerId > 0) Text("Mijoz saqlandi. ID: $customerId", color = Green, fontWeight = FontWeight.Bold)
                Button(enabled = !busy && customerId == 0, onClick = {
                    if (fullName.trim().length < 2 || phone.filter(Char::isDigit).length < 7 || !offer || !consent) {
                        message = "F.I.Sh., telefon va ikkala rozilikni to‘ldiring."
                    } else if (pinfl.isNotBlank() && pinfl.length != 14) {
                        message = "JSHSHIR 14 ta raqam bo‘lishi kerak."
                    } else if (passport.isNotBlank() && !Regex("^[A-Z]{2}[0-9]{7}$").matches(passport)) {
                        message = "Pasport AA1234567 ko‘rinishida bo‘lsin."
                    } else {
                        busy = true
                        scope.launch {
                            try {
                                val body = JSONObject().put("full_name", fullName.trim()).put("phone", phone.trim())
                                    .put("address", if (address.isBlank()) JSONObject.NULL else address.trim())
                                    .put("passport_number", if (passport.isBlank()) JSONObject.NULL else passport)
                                    .put("pinfl", if (pinfl.isBlank()) JSONObject.NULL else pinfl)
                                    .put("offer_accepted", offer).put("personal_data_consent", consent)
                                val result = JSONObject(api("POST", "/customers", session.token, body))
                                customerId = result.optInt("id")
                                if (customerId <= 0) throw Exception("Server mijoz ID raqamini qaytarmadi")
                                message = "Mijoz muvaffaqiyatli saqlandi."
                            } catch (e: Exception) { message = e.message ?: "Mijoz saqlanmadi" }
                            finally { busy = false }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("MIJOZNI SAQLASH") }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. SHARTNOMA", color = Green, fontWeight = FontWeight.Bold)
                Text(if (contractCode.isBlank()) "Mijoz saqlangach shartnoma so‘rovi yuboriladi." else "Shartnoma: $contractCode • Holat: $contractStatus", color = SecondaryText)
                Button(enabled = customerId > 0 && contractCode.isBlank() && !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val result = JSONObject(api("POST", "/contracts", session.token, JSONObject().put("customer_id", customerId)))
                            contractCode = result.optString("contract_code")
                            contractStatus = result.optString("status")
                            if (contractCode.isBlank()) throw Exception("Shartnoma kodi qaytmadi")
                            message = "Shartnoma so‘rovi yuborildi. Tasdiqni kuting."
                            refresh++
                        } catch (e: Exception) { message = e.message ?: "Shartnoma yaratilmadi" }
                        finally { busy = false }
                    }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("SHARTNOMA SO‘ROVINI YUBORISH") }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("3. CHIQIM", color = Green, fontWeight = FontWeight.Bold)
                Text("Shartnoma holati: " + contractStatus.ifBlank { "—" }, color = SecondaryText)
                OutlinedTextField(stockAmount, { stockAmount = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Chiqim summasi (UZS)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (stockStatus.isNotBlank()) Text("Chiqim holati: $stockStatus", color = Green)
                Button(enabled = contractCode.isNotBlank() && contractStatus == "approved" && stockStatus.isBlank() && !busy, onClick = {
                    val amount = stockAmount.toDoubleOrNull()
                    if (amount == null || amount <= 0) message = "Chiqim summasini to‘g‘ri kiriting."
                    else {
                        busy = true
                        scope.launch {
                            try {
                                api("POST", "/stock-outs", session.token, JSONObject().put("contract_code", contractCode).put("amount", amount))
                                stockStatus = "pending"
                                message = "Chiqim yuborildi. Tasdiqlanishini kuting."
                                refresh++
                            } catch (e: Exception) { message = e.message ?: "Chiqim saqlanmadi" }
                            finally { busy = false }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("CHIQIMNI SAQLASH") }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("4. FAKTURA", color = Green, fontWeight = FontWeight.Bold)
                Text("Chiqim holati: " + stockStatus.ifBlank { "—" }, color = SecondaryText)
                OutlinedTextField(supplier, { supplier = it }, label = { Text("Ta’minotchi") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(invoiceAmount, { invoiceAmount = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Faktura summasi (UZS)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (invoiceStatus.isNotBlank()) Text("Faktura holati: $invoiceStatus", color = Green)
                Button(enabled = contractCode.isNotBlank() && contractStatus == "approved" && stockStatus == "approved" && invoiceStatus.isBlank() && !busy, onClick = {
                    val amount = invoiceAmount.toDoubleOrNull()
                    if (supplier.trim().length < 2 || amount == null || amount <= 0) message = "Ta’minotchi va faktura summasini kiriting."
                    else {
                        busy = true
                        scope.launch {
                            try {
                                api("POST", "/invoices", session.token, JSONObject().put("contract_code", contractCode).put("amount", amount).put("supplier", supplier.trim()))
                                invoiceStatus = "pending"
                                message = "Faktura yuborildi. Tasdiqlanishini kuting."
                                refresh++
                            } catch (e: Exception) { message = e.message ?: "Faktura saqlanmadi" }
                            finally { busy = false }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("FAKTURANI SAQLASH") }
            }
        }
        if (message.isNotBlank()) MessageCard(message)
        OutlinedButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) { Text("TASDIQLASH HOLATLARINI YANGILASH") }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
fun ApiPage(session: Session, page: String, back: () -> Unit, selectedContractCode: String = "", onOpenStock: (String) -> Unit = {}, onOpenInvoice: (String, String) -> Unit = { _, _ -> }, selectedStockAmount: String = "", onContractCreated: () -> Unit = {}) {
    val title = when (page) {
        "workflow", "customers" -> "So‘rovnoma • 1/4 Mijoz"
        "contracts" -> "So‘rovnoma • 2/4 Shartnoma"
        "stock" -> "So‘rovnoma • 3/4 Chiqim"
        "invoices" -> "So‘rovnoma • 4/4 Faktura"
        "search" -> "Mijoz qidirish"
        "applications" -> "Moliyalashtirish arizalari"
        "employees" -> "Xodimlar"
        "reports" -> "Hisobotlar"
        else -> "Faoliyat tarixi"
    }
    var raw by remember(page) { mutableStateOf("") }
    var loading by remember(page) { mutableStateOf(true) }
    var error by remember(page) { mutableStateOf("") }
    var refresh by remember(page) { mutableIntStateOf(0) }
    var dialog by remember(page) { mutableStateOf((page == "stock" || page == "invoices") && selectedContractCode.isNotBlank()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(page, refresh) {
        loading = true
        error = ""
        try {
            raw = when (page) {
                "customers", "search", "workflow" -> api("GET", "/customers", session.token)
                "applications" -> api("GET", "/finance-applications", session.token)
                "employees" -> api("GET", "/users", session.token)
                "audit" -> api("GET", "/audit", session.token)
                "reports" -> JSONObject()
                    .put("customers", JSONArray(api("GET", "/customers", session.token)))
                    .put("applications", JSONArray(api("GET", "/applications", session.token))).toString()
                "contracts" -> api("GET", "/contracts", session.token)
                "stock" -> api("GET", "/stock-outs", session.token)
                "invoices" -> api("GET", "/invoices", session.token)
                else -> "[]"
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
                "customers", "workflow" -> {
                    ActionButton("＋ Yangi mijoz") { dialog = true }
                    CustomerRows(raw) { customerId ->
                        scope.launch {
                            try {
                                api("POST", "/contracts", session.token, JSONObject().put("customer_id", customerId))
                                onContractCreated()
                            } catch (e: Exception) { error = e.message ?: "Shartnoma yaratilmadi" }
                        }
                    }
                }
                "search" -> {
                    CustomerSearchRows(raw)
                }
                "contracts" -> {
                    ActionButton("＋ Shartnomaga so‘rov yuborish") { dialog = true }
                    ContractRows(raw, session, { refresh++ }, onOpenStock)
                }
                "stock" -> {
                    ActionButton("＋ Chiqim tovar kiritish") { dialog = true }
                    StockRows(raw, session, { refresh++ }, onOpenInvoice)
                }
                "invoices" -> {
                    ActionButton("＋ Faktura kiritish") { dialog = true }
                    InvoiceRows(raw, session) { refresh++ }
                }
                "applications" -> {
                    ActionButton("＋ Yangi ariza") { dialog = true }
                    FinanceApplicationRows(raw, session) { refresh++ }
                }
                "employees" -> {
                    ActionButton("＋ Xodim qo‘shish") { dialog = true }
                    EmployeeRows(raw, session) { refresh++ }
                }
                "audit" -> AuditRows(raw)
                "reports" -> ReportRows(raw)
            }
        }
    }

    if (dialog) {
        when (page) {
            "customers", "workflow" -> CustomerForm(
                close = { dialog = false },
                save = { name, phone, address, birthDate, passportSeries, passportNumber, pinfl, photoData, offerAccepted, personalDataConsent ->
                    scope.launch {
                        try {
                            val isoBirthDate = if (birthDate.isBlank()) "" else {
                                val parts = birthDate.split(".")
                                if (parts.size == 3 && parts[0].length == 2 && parts[1].length == 2 && parts[2].length == 4)
                                    parts[2] + "-" + parts[1] + "-" + parts[0]
                                else ""
                            }
                            val customerBody = JSONObject()
                                .put("full_name", name)
                                .put("phone", phone)
                                .put("address", if (address.isBlank()) JSONObject.NULL else address)
                                .put("birth_date", if (isoBirthDate.isBlank()) JSONObject.NULL else isoBirthDate)
                                .put("passport_number", if (passportSeries.isBlank() && passportNumber.isBlank()) JSONObject.NULL else passportSeries + passportNumber)
                                .put("pinfl", if (pinfl.isBlank()) JSONObject.NULL else pinfl)
                                .put("photo_data", if (photoData.isBlank()) JSONObject.NULL else photoData)
                                .put("offer_accepted", offerAccepted)
                                .put("personal_data_consent", personalDataConsent)
                            val createdCustomer = JSONObject(api("POST", "/customers", session.token, customerBody))
                            val customerId = createdCustomer.optInt("id")
                            if (page == "workflow" && customerId > 0) {
                                val contract = JSONObject(api("POST", "/contracts", session.token, JSONObject().put("customer_id", customerId)))
                                selectedContractCode = contract.text("contract_code")
                                page = "contracts"
                            } else {
                                dialog = false
                                refresh++
                            }
                        } catch (e: Exception) { error = e.message ?: "Saqlash xatosi"; dialog = false }
                    }
                }
            )
            "contracts" -> BusinessRecordForm(
                kind = "contract",
                close = { dialog = false },
                save = { customerId, _, _, _ ->
                    scope.launch {
                        try {
                            api("POST", "/contracts", session.token, JSONObject().put("customer_id", customerId))
                            dialog = false
                            refresh++
                        } catch (e: Exception) { error = e.message ?: "Shartnoma so‘rovi yuborilmadi"; dialog = false }
                    }
                }
            )
            "stock" -> BusinessRecordForm(
                kind = "stock",
                initialContractCode = selectedContractCode,
                close = { dialog = false },
                save = { _, contractId, amount, _ ->
                    scope.launch {
                        try {
                            api("POST", "/stock-outs", session.token, JSONObject().put("contract_code", contractId.toString()).put("amount", amount.toDouble()))
                            dialog = false
                            refresh++
                        } catch (e: Exception) { error = e.message ?: "Chiqim saqlanmadi"; dialog = false }
                    }
                }
            )
            "invoices" -> BusinessRecordForm(
                kind = "invoice",
                initialContractCode = selectedContractCode,
                initialAmount = selectedStockAmount,
                close = { dialog = false },
                save = { _, contractId, amount, supplier ->
                    scope.launch {
                        try {
                            api("POST", "/invoices", session.token, JSONObject().put("contract_code", contractId.toString()).put("amount", amount.toDouble()).put("supplier", supplier))
                            dialog = false
                            refresh++
                        } catch (e: Exception) { error = e.message ?: "Faktura saqlanmadi"; dialog = false }
                    }
                }
            )
            "applications" -> FinanceApplicationForm(
                close = { dialog = false },
                save = { body ->
                    scope.launch {
                        try {
                            api("POST", "/finance-applications", session.token, body)
                            dialog = false
                            refresh++
                        } catch (e: Exception) { error = e.message ?: "Ariza saqlash xatosi"; dialog = false }
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
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp), colors = CardDefaults.cardColors(containerColor = CardSurface)) {
        Text(message, Modifier.padding(16.dp), color = Color.DarkGray)
    }
}

@Composable
fun ContractRows(raw: String, session: Session, changed: () -> Unit, onOpenStock: (String) -> Unit = {}) {
    val arr = remember(raw) { JSONArray(raw) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var signingId by remember { mutableIntStateOf(0) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var signingStatus by remember { mutableStateOf("approved") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (arr.length() == 0) item { MessageCard("Hozircha shartnomalar yo‘q.") }
        items((0 until arr.length()).toList()) { i ->
            val o = arr.getJSONObject(i)
            val id = o.optInt("id")
            val status = o.text("status")
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Mijozning F.I.O si:", color = Color.Gray, fontSize = 12.sp)
                    Text(o.text("customer_name"), color = Green, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text("Shartnoma kodi: " + o.text("contract_code") + "  •  ID: #" + o.optInt("id"))
                    Text("Sanasi: " + o.text("created_at").take(10))
                    Text("Status: " + when(status) { "approved" -> "Shartnoma tasdiqlangan"; "rejected" -> "Rad etilgan"; else -> "Tasdiqlash kutilmoqda" }, color = if(status=="approved") Green else Color(0xFF9A6700), fontWeight = FontWeight.SemiBold)
                    if(status=="pending" && (session.role=="manager" || session.role=="admin")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick={ signingId = id; signingStatus = "approved"; showSignatureDialog = true }, colors=ButtonDefaults.buttonColors(containerColor=Green)) { Text("✍ Imzo + tasdiqlash") }
                            OutlinedButton(onClick={ signingId = id; signingStatus = "rejected"; showSignatureDialog = true }) { Text("Imzo + rad etish") }
                        }
                    }
                    if (status == "approved") {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { onOpenStock(o.text("contract_code")) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Green)
                        ) { Text("📦 Chiqimga o‘tish") }
                    }
                }
            }
        }
    }
    if (showSignatureDialog) {
        SignatureDialog(
            title = "Shartnoma bo‘yicha imzo",
            onDismiss = { showSignatureDialog = false; signingId = 0 },
            onConfirm = { signature ->
                val id = signingId
                val status = signingStatus
                showSignatureDialog = false
                signingId = 0
                scope.launch {
                    try {
                        api("PATCH", "/contracts/$id/review", session.token,
                            JSONObject().put("status", status).put("signature_data", signature))
                        changed()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, e.message ?: "Tasdiqlash xatosi", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}

@Composable
fun StockRows(raw: String, session: Session, changed: () -> Unit, onOpenInvoice: (String, String) -> Unit = { _, _ -> }) {
    val arr = remember(raw) { JSONArray(raw) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var signingId by remember { mutableIntStateOf(0) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var signingStatus by remember { mutableStateOf("approved") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if(arr.length()==0) item { MessageCard("Hozircha chiqim tovarlar yo‘q.") }
        items((0 until arr.length()).toList()) { i ->
            val o=arr.getJSONObject(i); val id=o.optInt("id"); val status=o.text("status")
            Card(Modifier.fillMaxWidth(), colors=CardDefaults.cardColors(containerColor=CardSurface), shape=RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Mijozning F.I.O si:", color=SecondaryText, fontSize=12.sp)
                    Text(o.text("customer_name"), color=Green, fontWeight=FontWeight.Bold)
                    Text("Shartnoma kodi: " + o.text("contract_code"))
                    Text("Summa: " + String.format("%,.0f",o.optDouble("amount")) + " UZS")
                    Text("Status: " + if(status=="approved") "Tasdiqlangan" else if(status=="rejected") "Rad etilgan" else "Tasdiqlash kutilmoqda", color=if(status=="approved") Green else Color(0xFF9A6700), fontWeight=FontWeight.SemiBold)
                    if(status=="pending" && (session.role=="manager" || session.role=="admin")) {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={ signingId = id; signingStatus = "approved"; showSignatureDialog = true },colors=ButtonDefaults.buttonColors(containerColor=Green)){Text("✍ Imzo + tasdiqlash")}
                            OutlinedButton(onClick={ signingId = id; signingStatus = "rejected"; showSignatureDialog = true }){Text("Imzo + rad etish")}
                        }
                    }
                    if (status == "approved") {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { onOpenInvoice(o.text("contract_code"), o.optDouble("amount").toLong().toString()) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Green)
                        ) { Text("🧾 Fakturaga o‘tish") }
                    }
                    if (o.text("signature_data").isNotBlank()) Text("✍ Imzo saqlangan", color = Green, fontSize = 12.sp)
                }
            }
        }
    }
    if (showSignatureDialog) {
        SignatureDialog(
            title = "Chiqim bo‘yicha imzo",
            onDismiss = { showSignatureDialog = false; signingId = 0 },
            onConfirm = { signature ->
                val id = signingId
                val status = signingStatus
                showSignatureDialog = false
                signingId = 0
                scope.launch {
                    try {
                        api("PATCH", "/stock-outs/$id/review", session.token,
                            JSONObject().put("status", status).put("signature_data", signature))
                        changed()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, e.message ?: "Tasdiqlash xatosi", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}

@Composable
fun InvoiceRows(raw: String, session: Session, changed: () -> Unit) {
    val arr=remember(raw){JSONArray(raw)}
    val context = LocalContext.current
    val scope=rememberCoroutineScope()
    var signingId by remember { mutableIntStateOf(0) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var signingStatus by remember { mutableStateOf("approved") }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(arr.length()==0) item { MessageCard("Hozircha fakturalar yo‘q.") }
        items((0 until arr.length()).toList()) { i ->
            val o=arr.getJSONObject(i); val id=o.optInt("id"); val status=o.text("status")
            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=CardSurface),shape=RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Ta’minotchi:",color=SecondaryText,fontSize=12.sp)
                    Text(o.text("supplier"),color=Green,fontWeight=FontWeight.Bold)
                    Text("Summa: " + String.format("%,.0f",o.optDouble("amount")) + " UZS")
                    Text("Shartnoma kodi: " + o.text("contract_code"))
                    Text("Status: " + if(status=="approved") "Faktura tasdiqlandi" else if(status=="rejected") "Rad etildi" else "Tasdiqlash kutilmoqda",color=if(status=="approved") Green else Color(0xFF9A6700),fontWeight=FontWeight.SemiBold)
                    if(status=="pending" && (session.role=="manager" || session.role=="admin")) {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={ signingId = id; signingStatus = "approved"; showSignatureDialog = true },colors=ButtonDefaults.buttonColors(containerColor=Green)){Text("✍ Imzo + tasdiqlash")}
                            OutlinedButton(onClick={ signingId = id; signingStatus = "rejected"; showSignatureDialog = true }){Text("Imzo + rad etish")}
                        }
                    }
                    if (o.text("signature_data").isNotBlank()) Text("✍ Imzo saqlangan", color = Green, fontSize = 12.sp)
                }
            }
        }
    }
    if (showSignatureDialog) {
        SignatureDialog(
            title = "Faktura bo‘yicha imzo",
            onDismiss = { showSignatureDialog = false; signingId = 0 },
            onConfirm = { signature ->
                val id = signingId
                val status = signingStatus
                showSignatureDialog = false
                signingId = 0
                scope.launch {
                    try {
                        api("PATCH", "/invoices/$id/review", session.token,
                            JSONObject().put("status", status).put("signature_data", signature))
                        changed()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, e.message ?: "Tasdiqlash xatosi", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}

@Composable
fun SignatureDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val points = remember { mutableStateListOf<Offset>() }
    var padWidth by remember { mutableFloatStateOf(1f) }
    var padHeight by remember { mutableFloatStateOf(1f) }
    var problem by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = Green, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Barmog‘ingiz bilan pastdagi maydonga imzo chizing.", color = PrimaryText, fontSize = 13.sp)
                Canvas(
                    Modifier.fillMaxWidth().height(170.dp)
                        .background(Color.White, RoundedCornerShape(10.dp))
                        .border(1.dp, Color.LightGray, RoundedCornerShape(10.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    padWidth = size.width.toFloat()
                                    padHeight = size.height.toFloat()
                                    points.add(it)
                                },
                                onDrag = { change, _ ->
                                    points.add(change.position)
                                    change.consume()
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { padWidth = size.width.toFloat(); padHeight = size.height.toFloat(); points.add(it) })
                        }
                ) {
                    points.zipWithNext().forEach { (a, b) ->
                        drawLine(Color.Black, a, b, strokeWidth = 4f)
                    }
                    if (points.size == 1) {
                        drawCircle(Color.Black, radius = 2.5f, center = points.first())
                    }
                }
                TextButton(onClick = { points.clear(); problem = "" }) { Text("Tozalash") }
                if (problem.isNotBlank()) Text(problem, color = Color.Red, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (points.size < 3) {
                        problem = "Avval barmog‘ingiz bilan imzo chizing."
                    } else {
                        try {
                            val bitmap = Bitmap.createBitmap(900, 450, Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(bitmap)
                            canvas.drawColor(android.graphics.Color.WHITE)
                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                color = android.graphics.Color.BLACK
                                strokeWidth = 8f
                                style = android.graphics.Paint.Style.STROKE
                                strokeCap = android.graphics.Paint.Cap.ROUND
                                strokeJoin = android.graphics.Paint.Join.ROUND
                            }
                            val scaleX = 900f / padWidth.coerceAtLeast(1f)
                            val scaleY = 450f / padHeight.coerceAtLeast(1f)
                            for (index in 0 until points.lastIndex) {
                                canvas.drawLine(
                                    points[index].x * scaleX,
                                    points[index].y * scaleY,
                                    points[index + 1].x * scaleX,
                                    points[index + 1].y * scaleY,
                                    paint
                                )
                            }
                            val output = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                            bitmap.recycle()
                            onConfirm(Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
                        } catch (e: Exception) {
                            problem = "Imzoni tayyorlashda xatolik yuz berdi."
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Green)
            ) { Text("Imzoni saqlash") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Bekor qilish") } }
    )
}

@Composable
fun BusinessRecordForm(kind: String, close: () -> Unit, save: (Int, Int, String, String) -> Unit, initialContractCode: String = "", initialAmount: String = "") {
    var customerId by remember { mutableStateOf("") }
    var contractId by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var supplier by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf("") }
    LaunchedEffect(initialContractCode, initialAmount) { if (initialContractCode.isNotBlank()) contractId = initialContractCode.filter(Char::isDigit); if (initialAmount.isNotBlank()) amount = initialAmount }
    val isContract=kind=="contract"
    val isStock=kind=="stock"
    val title=if(isContract) "Shartnomaga so‘rov" else if(isStock) "Chiqim tovar" else "Yangi faktura"
    AlertDialog(
        onDismissRequest=close,
        title={Text(title,color=Green,fontWeight=FontWeight.Bold)},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(isContract) OutlinedTextField(customerId,{customerId=it.filter(Char::isDigit)},label={Text("Mijoz ID raqami")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
                else {
                    OutlinedTextField(contractId,{contractId=it.filter(Char::isDigit)},label={Text("Shartnoma kodi (masalan 1000690587)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
                    OutlinedTextField(amount,{amount=it.filter{ch->ch.isDigit()||ch=='.'}},label={Text("Summa (UZS)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)
                    if(!isStock) OutlinedTextField(supplier,{supplier=it},label={Text("Ta’minotchi nomi")},singleLine=true)
                }
                if(problem.isNotBlank()) Text(problem,color=Color.Red,fontSize=12.sp)
            }
        },
        confirmButton={
            Button(onClick={
                val valid=if(isContract) customerId.toIntOrNull()!=null else contractId.toIntOrNull()!=null && amount.toDoubleOrNull()!=null && amount.toDoubleOrNull()!!>0 && (isStock || supplier.trim().length>=2)
                if(valid) save(customerId.toIntOrNull()?:0,contractId.toIntOrNull()?:0,amount,supplier.trim())
                else problem=if(isContract) "Mijoz ID raqamini kiriting." else "Shartnoma ID, summa va ta’minotchi ma’lumotlarini tekshiring."
            },colors=ButtonDefaults.buttonColors(containerColor=Green)){Text("Saqlash")}
        },
        dismissButton={TextButton(onClick=close){Text("Bekor qilish")}}
    )
}

@Composable
fun CustomerSearchRows(raw: String) {
    var query by remember { mutableStateOf("") }
    val customers = remember(raw) {
        try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Mijozni qidiring") },
            placeholder = { Text("F.I.Sh., telefon yoki pasport") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            singleLine = true,
            shape = RoundedCornerShape(14.dp)
        )
        val matches = (0 until customers.length()).map { customers.getJSONObject(it) }.filter { item ->
            val text = listOf(item.optString("full_name"), item.optString("phone"), item.optString("passport_number"), item.optString("pinfl"))
                .joinToString(" ").lowercase()
            text.contains(query.trim().lowercase())
        }
        if (matches.isEmpty()) {
            MessageCard(if (query.isBlank()) "Hozircha mijozlar yo‘q." else "Mos mijoz topilmadi.")
        } else {
            LazyColumn {
                items(matches) { item ->
                    Card(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = CardSurface),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(item.optString("full_name"), color = Green, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(item.optString("phone"), color = Color.DarkGray, fontSize = 13.sp)
                            val passport = item.optString("passport_number")
                            if (passport.isNotBlank() && passport != "null") Text("Pasport: $passport", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CustomerRows(raw: String, onCreateContract: (Int) -> Unit = {}) {
    val arr = remember(raw) { JSONArray(raw) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (arr.length() == 0) item { MessageCard("Hozircha mijozlar yo‘q.") }
        items((0 until arr.length()).toList()) { i ->
            val o = arr.getJSONObject(i)
            val photo = o.text("photo_data")
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardSurface)) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (photo.isNotBlank()) {
                        val bitmap = remember(photo) {
                            try {
                                val bytes = Base64.decode(photo, Base64.DEFAULT)
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            } catch (_: Exception) { null }
                        }
                        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "Mijoz surati", modifier = Modifier.size(64.dp))
                        else Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { Text("👤") }
                    } else {
                        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { Text("👤", fontSize = 28.sp) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("#" + o.optInt("id") + "  " + o.text("full_name"), color = Green, fontWeight = FontWeight.Bold)
                        Text("Telefon: " + o.text("phone"))
                        Text("Tug‘ilgan sana: " + o.text("birth_date"))
                        Text("Pasport: " + o.text("passport_number"))
                        Text("JSHSHIR: " + o.text("pinfl"))
                        Text("Manzil: " + o.text("address"))
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { onCreateContract(o.optInt("id")) },
                            colors = ButtonDefaults.buttonColors(containerColor = Green),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("📄 Shartnoma yaratish") }
                    }
                }
            }
        }
    }
}



private class FinanceProductDraft {
    var name by mutableStateOf("")
    var category by mutableStateOf("")
    var supplier by mutableStateOf("")
    var price by mutableStateOf("")
    var quantity by mutableStateOf("1")
}

@Composable
fun FinanceApplicationForm(close: () -> Unit, save: (JSONObject) -> Unit) {
    var customerId by remember { mutableStateOf("") }
    var term by remember { mutableStateOf("12") }
    var guarantorName by remember { mutableStateOf("") }
    var guarantorPhone by remember { mutableStateOf("") }
    var guarantorPassport by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf("") }
    val products = remember { mutableStateListOf(FinanceProductDraft()) }
    Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = LightGreen) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().background(CardSurface).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", color = Green, fontSize = 30.sp, modifier = Modifier.clickable { close() })
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Yangi ariza", color = Green, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("50% ustama + qo‘shimcha 5%", color = Color.Gray, fontSize = 12.sp)
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(customerId, { customerId = it.filter(Char::isDigit) }, label = { Text("Mijoz ID raqami") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("TOVARLAR", color = Green, fontWeight = FontWeight.Bold)
                    products.forEachIndexed { index, item ->
                        Card(colors = CardDefaults.cardColors(containerColor = CardSurface), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Tovar " + (index + 1), Modifier.weight(1f), color = Green, fontWeight = FontWeight.Bold)
                                    if (products.size > 1) Text("O‘chirish", color = Color.Red, modifier = Modifier.clickable { products.removeAt(index) })
                                }
                                OutlinedTextField(item.name, { item.name = it }, label = { Text("Tovar nomi") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(item.category, { item.category = it }, label = { Text("Kategoriya") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(item.supplier, { item.supplier = it }, label = { Text("Ta’minotchi") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(item.price, { item.price = it.filter(Char::isDigit) }, label = { Text("Narxi (UZS)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1.4f), singleLine = true)
                                    OutlinedTextField(item.quantity, { item.quantity = it.filter(Char::isDigit) }, label = { Text("Miqdori") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(0.7f), singleLine = true)
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { if (products.size < 30) products.add(FinanceProductDraft()) }, modifier = Modifier.fillMaxWidth()) { Text("＋ Tovar qo‘shish") }
                    Text("SHARTLAR", color = Green, fontWeight = FontWeight.Bold)
                    OutlinedTextField(term, { term = it.filter(Char::isDigit).take(2) }, label = { Text("Muddat (oy)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("50% ustama + 5% qo‘shimcha = 57,5%", color = Green, fontWeight = FontWeight.SemiBold)
                    Text("KAFIL (ixtiyoriy)", color = Green, fontWeight = FontWeight.Bold)
                    OutlinedTextField(guarantorName, { guarantorName = it }, label = { Text("Kafil F.I.Sh.") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(guarantorPhone, { guarantorPhone = it }, label = { Text("Kafil telefoni") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(guarantorPassport, { guarantorPassport = it }, label = { Text("Kafil pasporti") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (problem.isNotBlank()) Text(problem, color = Color.Red, fontSize = 12.sp)
                }
                Button(onClick = {
                    val cid = customerId.toIntOrNull()
                    val months = term.toIntOrNull()
                    val validProducts = products.all { it.name.trim().length >= 2 && (it.price.toDoubleOrNull() ?: 0.0) > 0 && (it.quantity.toIntOrNull() ?: 0) > 0 }
                    if (cid == null || months == null || months !in 1..60 || !validProducts) {
                        problem = "Mijoz ID va barcha tovarlarning nomi, narxi, miqdorini tekshiring."
                    } else if (guarantorName.isNotBlank() && guarantorPhone.filter(Char::isDigit).length < 7) {
                        problem = "Kafil telefon raqamini to‘liq kiriting."
                    } else {
                        val productArray = JSONArray()
                        products.forEach { item ->
                            productArray.put(JSONObject().put("name", item.name.trim()).put("category", item.category.trim()).put("supplier", item.supplier.trim()).put("unit_price", item.price.toDouble()).put("quantity", item.quantity.toInt()))
                        }
                        val guarantors = JSONArray()
                        if (guarantorName.isNotBlank()) guarantors.put(JSONObject().put("full_name", guarantorName.trim()).put("phone", guarantorPhone.trim()).put("passport_number", guarantorPassport.trim()))
                        save(JSONObject().put("customer_id", cid).put("term_months", months).put("products", productArray).put("guarantors", guarantors))
                    }
                }, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Arizani yuborish") }
            }
        }
    }
}

@Composable
fun FinanceApplicationRows(raw: String, session: Session, changed: () -> Unit) {
    val arr = remember(raw) { JSONArray(raw) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (arr.length() == 0) item { MessageCard("Hozircha arizalar yo‘q.") }
        items((0 until arr.length()).toList()) { i ->
            val o = arr.getJSONObject(i)
            val id = o.optInt("id")
            val status = o.text("status")
            val products = o.optJSONArray("products") ?: JSONArray()
            val schedule = o.optJSONArray("schedule") ?: JSONArray()
            val guarantors = o.optJSONArray("guarantors") ?: JSONArray()
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedCornerShape(15.dp)) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Ariza #$id • " + o.text("customer_name"), color = Green, fontWeight = FontWeight.Bold)
                    Text("TOVARLAR", color = Green, fontWeight = FontWeight.Bold)
                    for (j in 0 until products.length()) {
                        val p = products.getJSONObject(j)
                        Text(p.text("name") + " • " + p.optInt("quantity") + " dona")
                        Text("Ta’minotchi: " + p.text("supplier"))
                        Text("Narxi: " + String.format("%,.0f", p.optDouble("line_total")) + " so‘m")
                    }
                    Text("Tovarlar jami: " + String.format("%,.0f", o.optDouble("base_amount")) + " so‘m")
                    Text("Ustama: 50% + 5%")
                    Text("Muddat: " + o.optInt("term_months") + " oy")
                    Text("JAMI: " + String.format("%,.0f", o.optDouble("total_amount")) + " so‘m", color = Green, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text("Holat: " + when(status) { "approved" -> "Tasdiqlangan"; "rejected" -> "Rad etilgan"; else -> "Ko‘rib chiqilmoqda" }, color = if(status=="approved") Green else Color(0xFF9A6700), fontWeight = FontWeight.SemiBold)
                    if (guarantors.length() > 0) {
                        Text("KAFILLAR", color = Green, fontWeight = FontWeight.Bold)
                        for (j in 0 until guarantors.length()) {
                            val g = guarantors.getJSONObject(j)
                            Text(g.text("full_name") + " • " + g.text("phone"))
                        }
                    }
                    Text("GRAFIK", color = Green, fontWeight = FontWeight.Bold)
                    for (j in 0 until schedule.length()) {
                        val s = schedule.getJSONObject(j)
                        Text(s.optInt("number").toString() + ". " + s.text("due_date") + " — " + String.format("%,.0f", s.optDouble("amount")) + " so‘m")
                    }
                    Button(onClick = { shareFinancePdf(context, o) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("PDF qilib ulashish") }
                    if (status == "pending" && (session.role == "manager" || session.role == "admin")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scope.launch { try { api("PATCH", "/finance-applications/$id/review", session.token, JSONObject().put("status", "approved")); changed() } catch (_: Exception) { } } }, colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Tasdiqlash") }
                            OutlinedButton(onClick = { scope.launch { try { api("PATCH", "/finance-applications/$id/review", session.token, JSONObject().put("status", "rejected")); changed() } catch (_: Exception) { } } }) { Text("Rad etish") }
                        }
                    }
                }
            }
        }
    }
}

private fun shareFinancePdf(context: android.content.Context, data: JSONObject) {
    try {
        val document = android.graphics.pdf.PdfDocument()
        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = android.graphics.Color.BLACK }
        var y = 48f
        fun line(value: String, bold: Boolean = false) {
            paint.textSize = if (bold) 16f else 11f
            paint.isFakeBoldText = bold
            canvas.drawText(value.take(90), 40f, y, paint)
            y += if (bold) 28f else 20f
        }
        line("NEXUS CRM — TO‘LOV GRAFIGI", true)
        line("Ariza № " + data.optInt("id"))
        line("Mijoz: " + data.optString("customer_name"))
        line("Tovarlar jami: " + String.format("%,.0f", data.optDouble("base_amount")) + " so‘m")
        line("Ustama: 50% + qo‘shimcha 5%")
        line("Muddat: " + data.optInt("term_months") + " oy")
        line("JAMI TO‘LOV: " + String.format("%,.0f", data.optDouble("total_amount")) + " so‘m", true)
        y += 12f
        line("TO‘LOV GRAFIGI", true)
        val schedule = data.optJSONArray("schedule") ?: JSONArray()
        for (i in 0 until schedule.length()) {
            val item = schedule.getJSONObject(i)
            line(item.optInt("number").toString() + ". " + item.optString("due_date") + " — " + String.format("%,.0f", item.optDouble("amount")) + " so‘m")
        }
        document.finishPage(page)
        val file = java.io.File(context.cacheDir, "NEXUS_Tolov_Grafigi_" + data.optInt("id") + ".pdf")
        file.outputStream().use { document.writeTo(it) }
        document.close()
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "To‘lov grafigini ulashish"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "PDF yaratishda xato: " + (e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
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
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardSurface)) {
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
fun EmployeeRows(raw: String, session: Session, changed: () -> Unit) {
    val arr = remember(raw) { JSONArray(raw) }
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (arr.length() == 0) item { MessageCard("Xodimlar topilmadi.") }
        items((0 until arr.length()).toList()) { i ->
            val o = arr.getJSONObject(i)
            val active = o.optBoolean("is_active", true)
            Card(Modifier.fillMaxWidth().clickable { selected = JSONObject(o.toString()) },
                colors = CardDefaults.cardColors(containerColor = CardSurface), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(o.text("full_name"), color = Green, fontWeight = FontWeight.Bold)
                    Text("Login: " + o.text("username"))
                    Text("Rol: " + o.text("role"))
                    Text(if (active) "Holat: Faol" else "Holat: Arxivlangan",
                        color = if (active) Green else Color(0xFFB45309), fontWeight = FontWeight.SemiBold)
                    Text("Boshqarish uchun bosing", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
    selected?.let { employee ->
        var name by remember(employee) { mutableStateOf(employee.text("full_name")) }
        var login by remember(employee) { mutableStateOf(employee.text("username")) }
        var role by remember(employee) { mutableStateOf(employee.text("role")) }
        var newPassword by remember(employee) { mutableStateOf("") }
        var busy by remember(employee) { mutableStateOf(false) }
        var localError by remember(employee) { mutableStateOf("") }
        val id = employee.optInt("id")
        val active = employee.optBoolean("is_active", true)
        AlertDialog(
            onDismissRequest = { if (!busy) selected = null },
            title = { Text("Xodimni boshqarish") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    Text("Xodim ID: #$id", color = Color.Gray, fontSize = 12.sp)
                    OutlinedTextField(name, { name = it }, label = { Text("F.I.Sh.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(login, { login = it }, label = { Text("Login") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Lavozim", fontWeight = FontWeight.SemiBold)
                    listOf("operator", "manager", "admin").forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = role == item, onClick = { role = item })
                            Text(item)
                        }
                    }
                    Divider()
                    OutlinedTextField(newPassword, { newPassword = it },
                        label = { Text("Yangi parol (ixtiyoriy)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Parolni o‘zgartirmasangiz, maydonni bo‘sh qoldiring.", color = Color.Gray, fontSize = 11.sp)
                    if (localError.isNotBlank()) Text(localError, color = Color.Red, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(enabled = !busy, onClick = {
                        if (name.trim().length < 2 || login.trim().length < 3) localError = "F.I.Sh. va loginni tekshiring."
                        else if (newPassword.isNotBlank() && newPassword.length < 8) localError = "Yangi parol kamida 8 belgidan iborat bo‘lsin."
                        else {
                            busy = true
                            scope.launch {
                                try {
                                    api("PATCH", "/users/$id", session.token,
                                        JSONObject().put("full_name", name.trim()).put("username", login.trim()).put("role", role))
                                    if (newPassword.isNotBlank()) api("PATCH", "/users/$id/password", session.token,
                                        JSONObject().put("new_password", newPassword))
                                    selected = null
                                    changed()
                                } catch (e: Exception) {
                                    localError = e.message ?: "Saqlashda xato"
                                } finally { busy = false }
                            }
                        }
                    }) { Text(if (busy) "Saqlanmoqda..." else "Saqlash") }
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                api("PATCH", "/users/$id/status?active=${!active}", session.token)
                                selected = null
                                changed()
                            } catch (e: Exception) {
                                localError = e.message ?: "Holatni o‘zgartirib bo‘lmadi"
                            } finally { busy = false }
                        }
                    }) { Text(if (active) "Arxivlash" else "Qayta faollashtirish", color = if (active) Color(0xFFB45309) else Green) }
                    TextButton(enabled = !busy, onClick = { selected = null }) { Text("Yopish") }
                }
            }
        )
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
    Card(Modifier.fillMaxWidth().padding(bottom = 9.dp), colors = CardDefaults.cardColors(containerColor = CardSurface)) {
        Row(Modifier.fillMaxWidth().padding(17.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(value.toString(), color = Green, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }
}

@Composable
fun CustomerForm(close: () -> Unit, save: (String, String, String, String, String, String, String, String, Boolean, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var offerAccepted by remember { mutableStateOf(false) }
    var personalDataConsent by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var birthDate by remember { mutableStateOf("") }
    var passportSeries by remember { mutableStateOf("") }
    var passportNumber by remember { mutableStateOf("") }
    var pinfl by remember { mutableStateOf("") }
    var photoData by remember { mutableStateOf("") }
    var cameraError by remember { mutableStateOf("") }
    var showCamera by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showCamera = true else cameraError = "Kameradan foydalanish uchun ruxsat bering."
    }

    Dialog(
        onDismissRequest = close,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF4F7FF)
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(CardSurface).padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("‹", color = Green, fontSize = 32.sp, modifier = Modifier.clickable { close() })
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Mijoz qo‘shish", color = Color(0xFF10264D), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Mijoz ma’lumotlarini kiriting", color = SecondaryText, fontSize = 12.sp)
                    }
                    Text("Mrpolatjan CRM", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Column(
                    Modifier.fillMaxSize().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.size(142.dp).background(Color(0xFFDCE8FF), androidx.compose.foundation.shape.CircleShape)
                            .clickable {
                                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) showCamera = true
                                else cameraPermission.launch(android.Manifest.permission.CAMERA)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (photoData.isNotBlank()) {
                            val preview = remember(photoData) {
                                try {
                                    val bytes = Base64.decode(photoData, Base64.DEFAULT)
                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                } catch (_: Exception) { null }
                            }
                            if (preview != null) Image(
                                preview.asImageBitmap(),
                                contentDescription = "Mijoz surati",
                                modifier = Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.CircleShape),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            ) else Text("👤", fontSize = 58.sp)
                        } else {
                            Text("👤", color = Color(0xFF6F91D0), fontSize = 66.sp)
                        }
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(44.dp)
                                .background(Green, androidx.compose.foundation.shape.CircleShape)
                                .clickable {
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) showCamera = true
                                    else cameraPermission.launch(android.Manifest.permission.CAMERA)
                                },
                            contentAlignment = Alignment.Center
                        ) { Text("📷", fontSize = 20.sp) }
                    }
                    Text(if (photoData.isBlank()) "Yuz rasmini olish" else "Surat biriktirildi", color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = CardSurface),
                        elevation = CardDefaults.cardElevation(3.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("SHAXSIY MA’LUMOTLAR", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(name, { name = it }, label = { Text("F.I.Sh.") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp))
                            Spacer(Modifier.height(9.dp))
                            OutlinedTextField(phone, { phone = it }, label = { Text("Telefon raqami") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("PASPORT MA’LUMOTLARI", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(9.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    passportSeries,
                                    { passportSeries = normalizePassportSeries(it) },
                                    label = { Text("Seriya") }, placeholder = { Text("AA") },
                                    singleLine = true, modifier = Modifier.weight(0.8f), shape = RoundedCornerShape(13.dp)
                                )
                                OutlinedTextField(
                                    passportNumber,
                                    { passportNumber = it.filter(Char::isDigit).take(7) },
                                    label = { Text("Pasport raqami") }, placeholder = { Text("1234567") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true, modifier = Modifier.weight(1.4f), shape = RoundedCornerShape(13.dp)
                                )
                            }
                            Spacer(Modifier.height(9.dp))
                            OutlinedTextField(
                                pinfl, { pinfl = it.filter(Char::isDigit).take(14) },
                                label = { Text("JSHSHIR (14 raqam)") }, placeholder = { Text("14 xonali raqam") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)
                            )
                            Spacer(Modifier.height(9.dp))
                            OutlinedTextField(
                                birthDate,
                                { birthDate = it.filter { ch -> ch.isDigit() || ch == '.' }.take(10) },
                                label = { Text("Tug‘ilgan sana") }, placeholder = { Text("KK.OO.YYYY") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)
                            )
                            Spacer(Modifier.height(9.dp))
                            OutlinedTextField(address, { address = it }, label = { Text("Manzil") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp))
                            Spacer(Modifier.height(14.dp))
                            Text("ROZILIK VA OFERTA", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.Top) {
                                Checkbox(checked = offerAccepted, onCheckedChange = { offerAccepted = it })
                                Column(Modifier.weight(1f).padding(top = 10.dp)) {
                                    Text("Ommaviy oferta shartlari bilan tanishdim va roziman", fontSize = 13.sp, color = PrimaryText)
                                }
                            }
                            Row(verticalAlignment = Alignment.Top) {
                                Checkbox(checked = personalDataConsent, onCheckedChange = { personalDataConsent = it })
                                Column(Modifier.weight(1f).padding(top = 10.dp)) {
                                    Text("Shaxsga doir ma’lumotlarimni yig‘ish va qayta ishlashga roziman", fontSize = 13.sp, color = Color(0xFF263650))
                                    Text("Rozilik mijozga tushuntirilgandan keyin belgilanadi.", fontSize = 11.sp, color = Color.Gray)
                                }
                            }
                            if (cameraError.isNotBlank()) Text(cameraError, color = Color.Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            val dateOk = birthDate.isBlank() || Regex("^\\d{2}\\.\\d{2}\\.\\d{4}$").matches(birthDate)
                            val normalizedSeries = normalizePassportSeries(passportSeries)
                            val passportOk = (normalizedSeries.isBlank() && passportNumber.isBlank()) ||
                                (normalizedSeries.matches(Regex("^[A-Z]{2}$")) && passportNumber.matches(Regex("^\\d{7}$")))
                            val pinflOk = pinfl.isBlank() || pinfl.matches(Regex("^\\d{14}$"))
                            val nameOk = name.trim().length >= 2
                            val phoneOk = phone.filter(Char::isDigit).length >= 7
                            if (nameOk && phoneOk && dateOk && passportOk && pinflOk && offerAccepted && personalDataConsent) {
                                save(name.trim(), phone.trim(), address.trim(), birthDate, normalizedSeries, passportNumber, pinfl, photoData, offerAccepted, personalDataConsent)
                            } else {
                                cameraError = when {
                                    !nameOk -> "F.I.Sh. kamida 2 ta belgidan iborat bo‘lsin."
                                    !phoneOk -> "Telefon raqamini to‘liq kiriting."
                                    !dateOk -> "Tug‘ilgan sana KK.OO.YYYY formatida bo‘lishi kerak."
                                    !passportOk -> "Pasport seriyasi 2 ta harf, raqami 7 ta raqam bo‘lishi kerak."
                                    !pinflOk -> "JSHSHIR aynan 14 ta raqamdan iborat bo‘lishi kerak."
                                    !offerAccepted -> "Ommaviy oferta shartlariga rozilikni belgilang."
                                    !personalDataConsent -> "Shaxsiy ma’lumotlarni qayta ishlashga rozilikni belgilang."
                                    else -> "Ma’lumotlarni tekshiring."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green)
                    ) { Text("✓   Saqlash", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    if (showCamera) {
        Dialog(
            onDismissRequest = { showCamera = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            CameraCaptureScreen(
                onClose = { showCamera = false },
                onCaptured = { bitmap ->
                    val scaled = Bitmap.createScaledBitmap(bitmap, minOf(bitmap.width, 720), minOf(bitmap.height, 720), true)
                    val output = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 78, output)
                    photoData = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                    cameraError = ""
                    showCamera = false
                }
            )
        }
    }
}

@Composable
fun CameraCaptureScreen(
    onClose: () -> Unit,
    onCaptured: (Bitmap) -> Unit,
    requireLiveness: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var livenessMessage by remember { mutableStateOf("Kameraga qarab, ko‘zingizni ochiq tuting") }
    var livenessPassed by remember { mutableStateOf(!requireLiveness) }
    val controller = remember(requireLiveness) {
        androidx.camera.view.LifecycleCameraController(context).apply {
            cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA
            setEnabledUseCases(
                androidx.camera.view.CameraController.IMAGE_CAPTURE or
                    (if (requireLiveness) androidx.camera.view.CameraController.IMAGE_ANALYSIS else 0)
            )
        }
    }
    val livenessAnalyzer = remember(requireLiveness) {
        if (requireLiveness) FaceLivenessAnalyzer(
            onProgress = { livenessMessage = it },
            onPassed = { livenessPassed = true }
        ) else null
    }
    DisposableEffect(lifecycleOwner, controller, livenessAnalyzer) {
        controller.bindToLifecycle(lifecycleOwner)
        if (livenessAnalyzer != null) {
            controller.setImageAnalysisAnalyzer(
                androidx.core.content.ContextCompat.getMainExecutor(context),
                livenessAnalyzer
            )
        }
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            livenessAnalyzer?.close()
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF18243D))) {
        AndroidView(
            factory = { ctx ->
                androidx.camera.view.PreviewView(ctx).apply {
                    this.controller = controller
                    scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("‹", color = Color.White, fontSize = 34.sp, modifier = Modifier.clickable { onClose() })
                Spacer(Modifier.width(18.dp))
                Text(
                    if (requireLiveness) "Jonlilik tekshiruvi" else "Rasmni tanlang",
                    color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(290.dp).border(3.dp, Color.White, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(278.dp).border(1.dp, Color(0xFF8EB5FF), androidx.compose.foundation.shape.CircleShape))
            }
            Text(
                if (requireLiveness) livenessMessage else "Yuzingizni doira ichiga joylashtiring",
                color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp)
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.padding(bottom = 42.dp).size(82.dp)
                    .border(5.dp, Color.White, androidx.compose.foundation.shape.CircleShape)
                    .padding(7.dp)
                    .background(
                        if (!requireLiveness || livenessPassed) Green else Color.DarkGray,
                        androidx.compose.foundation.shape.CircleShape
                    )
                    .clickable(enabled = !requireLiveness || livenessPassed) {
                        val photoFile = java.io.File(context.cacheDir, "client_${System.currentTimeMillis()}.jpg")
                        val outputOptions = androidx.camera.core.ImageCapture.OutputFileOptions.Builder(photoFile).build()
                        controller.takePicture(
                            outputOptions,
                            androidx.core.content.ContextCompat.getMainExecutor(context),
                            object : androidx.camera.core.ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(result: androidx.camera.core.ImageCapture.OutputFileResults) {
                                    val bitmap = android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath)
                                    photoFile.delete()
                                    if (bitmap != null) onCaptured(bitmap)
                                }
                                override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                                    photoFile.delete()
                                    android.util.Log.e("MrpolatjanCRM", "Camera capture failed", exception)
                                }
                            }
                        )
                    }
            )
        }
    }
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