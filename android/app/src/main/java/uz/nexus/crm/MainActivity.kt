
package uz.nexus.crm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

// SPLASH SCREEN

@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Green),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "ISHONCH",
                fontSize = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Ishonch bilan boshqaruv",
                color = Color.White,
                fontSize = 16.sp
            )

            Spacer(Modifier.height(35.dp))

            CircularProgressIndicator(
                color = Color.White
            )

            Spacer(Modifier.height(35.dp))

            Text(
                text = "NEXUS CRM PRO",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// SERVER LOGIN

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
            "Content-Type",
            "application/json"
        )

        connection.setRequestProperty(
            "Accept",
            "application/json"
        )

        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
        }.toString()

        connection.outputStream.use {
            it.write(body.toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode

        val responseStream =
            if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

        val response = responseStream
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()

        if (responseCode == 200) {
            JSONObject(response).getString("access_token")
        } else {
            val message = try {
                JSONObject(response).optString(
                    "detail",
                    "Server xatosi: $responseCode"
                )
            } catch (_: Exception) {
                "Server xatosi: $responseCode"
            }

            throw Exception(message)
        }
    } finally {
        connection.disconnect()
    }
}

// LOGIN SCREEN

@Composable
fun LoginScreen() {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var loggedIn by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    if (loggedIn) {
        DashboardScreen(username)
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
                text = "NEXUS CRM PRO",
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold,
                color = Green
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Boshqaruv tizimiga kirish",
                color = Color.DarkGray
            )

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
                Text(
                    text = error,
                    color = Color.Red
                )

                Spacer(Modifier.height(12.dp))
            }

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
                                error = e.message
                                    ?: "Ulanishda xatolik"
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
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Green
                )
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

// MAIN DASHBOARD

@Composable
fun DashboardScreen(username: String) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = LightGreen
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "NEXUS CRM PRO",
                        fontSize = 23.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Green
                    )

                    Text(
                        text = "Boshqaruv paneli",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }

                Surface(
                    shape = RoundedCornerShape(50.dp),
                    color = Green
                ) {
                    Text(
                        text = username.take(1).uppercase(),
                        modifier = Modifier.padding(
                            horizontal = 15.dp,
                            vertical = 10.dp
                        ),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(25.dp))

            Text(
                text = "Xush kelibsiz, $username!",
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = Color.DarkGray
            )

            Text(
                text = "Bugungi ish faoliyatingiz",
                color = Color.Gray,
                fontSize = 14.sp
            )

            Spacer(Modifier.height(22.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    title = "Mijozlar",
                    value = "0",
                    modifier = Modifier.weight(1f)
                )

                StatCard(
                    title = "Arizalar",
                    value = "0",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    title = "Tasdiqlangan",
                    value = "0",
                    modifier = Modifier.weight(1f)
                )

                StatCard(
                    title = "Jarayonda",
                    value = "0",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(25.dp))

            Text(
                text = "ASOSIY BO‘LIMLAR",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Green
            )

            Spacer(Modifier.height(12.dp))

            ModuleCard(
                emoji = "👥",
                title = "Mijozlar",
                description = "Mijozlar ro‘yxati"
            )

            ModuleCard(
                emoji = "📋",
                title = "Arizalar",
                description = "Yangi arizalar va ularning holati"
            )

            ModuleCard(
                emoji = "👨‍💼",
                title = "Xodimlar",
                description = "Operator va menejerlar"
            )

            ModuleCard(
                emoji = "📊",
                title = "Hisobotlar",
                description = "Statistika va KPI"
            )

            ModuleCard(
                emoji = "🕘",
                title = "Faoliyat tarixi",
                description = "Amallar va audit jurnali"
            )

            Spacer(Modifier.weight(1f))

            Text(
                text = "NEXUS CRM PRO • Boshqaruv tizimi",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = Color.Gray,
                fontSize = 11.sp
            )
        }
    }
}

// STATISTICS CARD

@Composable
fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = value,
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold,
                color = Green
            )

            Text(
                text = title,
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}

// MODULE CARD

@Composable
fun ModuleCard(
    emoji: String,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 9.dp),
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = emoji,
                fontSize = 25.sp
            )

            Spacer(Modifier.width(14.dp))

            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    color = Color.DarkGray
                )

                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}
