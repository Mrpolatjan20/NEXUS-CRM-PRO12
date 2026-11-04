
package uz.nexus.crm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Green = Color(0xFF087C42)
private val LightGreen = Color(0xFFE8F5EC)
private const val API_URL =
    "https://nexus-crm-backend-g8or.onrender.com"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                NexusApp()
            }
        }
    }
}

@Composable
fun NexusApp() {
    var showSplash by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3000)
        showSplash = false
    }

    if (showSplash) {
        SplashScreen()
    } else {
        LoginScreen()
    }
}

@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Green),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "ISHONCH",
                fontSize = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Ishonch bilan boshqaruv",
                color = Color.White
            )
            Spacer(Modifier.height(35.dp))
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(35.dp))
            Text(
                "NEXUS CRM PRO",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

suspend fun loginToServer(
    username: String,
    password: String
): String = withContext(Dispatchers.IO) {

    val connection = URL("$API_URL/auth/login")
        .openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.doOutput = true
        connection.setRequestProperty(
            "Content-Type", "application/json"
        )
        connection.setRequestProperty(
            "Accept", "application/json"
        )

        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
        }.toString()

        connection.outputStream.use {
            it.write(body.toByteArray(Charsets.UTF_8))
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        val response = stream
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()

        if (code == 200) {
            JSONObject(response).getString("access_token")
        } else {
            val message = try {
                JSONObject(response).optString(
                    "detail", "Server xatosi: $code"
                )
            } catch (_: Exception) {
                "Server xatosi: $code"
            }
            throw Exception(message)
        }
    } finally {
        connection.disconnect()
    }
}

@Composable
fun LoginScreen() {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var loggedIn by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    if (loggedIn) {
        DashboardScreen(
            username = username,
            onLogout = {
                loggedIn = false
                password = ""
            }
        )
        return
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = LightGreen
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "NEXUS CRM PRO",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Green
            )

            Text("Boshqaruv tizimiga kirish")

            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    error = ""
                },
                label = { Text("Login") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    error = ""
                },
                label = { Text("Parol") },
                visualTransformation =
                    PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))

            if (error.isNotEmpty()) {
                Text(error, color = Color.Red)
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    if (username.isBlank() || password.isBlank()) {
                        error = "Login va parolni kiriting"
                    } else {
                        loading = true
                        error = ""

                        scope.launch {
                            try {
                                val token = loginToServer(
                                    username.trim(),
                                    password
                                )
                                loggedIn = token.isNotBlank()
                            } catch (e: Exception) {
                                error = e.message ?: "Ulanish xatosi"
                            } finally {
                                loading = false
                            }
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Green
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White
                    )
                } else {
                    Text("TIZIMGA KIRISH")
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(
    username: String,
    onLogout: () -> Unit
) {
    var currentPage by remember { mutableStateOf("home") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = LightGreen
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "NEXUS CRM PRO",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Green
                    )
                    Text(
                        "Boshqaruv tizimi",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }

                Text(
                    "Chiqish",
                    color = Color.Red,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        onLogout()
                    }.padding(8.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            if (currentPage == "home") {
                HomePage(username) { page ->
                    currentPage = page
                }
            } else {
                InnerPage(
                    page = currentPage,
                    onBack = { currentPage = "home" }
                )
            }
        }
    }
}

@Composable
fun HomePage(
    username: String,
    onOpenPage: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Xush kelibsiz, $username!",
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            color = Color.DarkGray
        )

        Text(
            "Bugungi ish faoliyatingiz",
            color = Color.Gray
        )

        Spacer(Modifier.height(20.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard("Mijozlar", "0", Modifier.weight(1f))
            StatCard("Arizalar", "0", Modifier.weight(1f))
        }

        Spacer(Modifier.height(10.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard("Tasdiqlangan", "0", Modifier.weight(1f))
            StatCard("Jarayonda", "0", Modifier.weight(1f))
        }

        Spacer(Modifier.height(24.dp))

        Text(
            "ASOSIY BO‘LIMLAR",
            fontWeight = FontWeight.Bold,
            color = Green
        )

        Spacer(Modifier.height(12.dp))

        ModuleCard("👥", "Mijozlar", "Mijozlar ro‘yxati") {
            onOpenPage("customers")
        }

        ModuleCard("📋", "Arizalar", "Arizalarni boshqarish") {
            onOpenPage("applications")
        }

        ModuleCard("👨‍💼", "Xodimlar", "Operator va menejerlar") {
            onOpenPage("employees")
        }

        ModuleCard("📊", "Hisobotlar", "Statistika va KPI") {
            onOpenPage("reports")
        }

        ModuleCard("🕘", "Faoliyat tarixi", "Amallar tarixi") {
            onOpenPage("audit")
        }
    }
}

@Composable
fun InnerPage(
    page: String,
    onBack: () -> Unit
) {
    val title = when (page) {
        "customers" -> "Mijozlar"
        "applications" -> "Arizalar"
        "employees" -> "Xodimlar"
        "reports" -> "Hisobotlar"
        "audit" -> "Faoliyat tarixi"
        else -> "Bo‘lim"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "← Orqaga",
            color = Green,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable { onBack() }
                .padding(vertical = 12.dp)
        )

        Spacer(Modifier.height(12.dp))

        Text(
            title,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            color = Green
        )

        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    "Bu bo‘limning server bilan ishlash funksiyasi " +
                        "keyingi bosqichda ulanadi.",
                    color = Color.Gray
                )

                Spacer(Modifier.height(16.dp))

                if (page == "customers") {
                    Button(
                        onClick = { },
                        enabled = false,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Green
                        )
                    ) {
                        Text("Yangi mijoz qo‘shish")
                    }
                }

                if (page == "applications") {
                    Button(
                        onClick = { },
                        enabled = false,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Green
                        )
                    ) {
                        Text("Yangi ariza")
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        )
    ) {
        Column(Modifier.padding(15.dp)) {
            Text(
                value,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Green
            )
            Text(
                title,
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun ModuleCard(
    emoji: String,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 26.sp)

            Spacer(Modifier.width(14.dp))

            Column {
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    color = Color.DarkGray
                )
                Text(
                    description,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

