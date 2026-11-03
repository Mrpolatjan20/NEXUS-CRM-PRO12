
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
        kotlinx.coroutines.delay(10_000)
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
                color = Color.White,
                fontSize = 16.sp
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
                    "detail", "Server xatosi: $responseCode"
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
                "NEXUS CRM PRO",
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold,
                color = Green
            )

            Text(
                "Boshqaruv tizimiga kirish",
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
                Text(error, color = Color.Red)
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

@Composable
fun DashboardScreen(username: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightGreen)
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
        Spacer(Modifier.height(20.dp))
        Text("Xush kelibsiz, $username!")
        Spacer(Modifier.height(12.dp))
        Text("Tizimga muvaffaqiyatli kirdingiz.")
    }
}
