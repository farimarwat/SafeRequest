package com.farimarwat.saferequestexample

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.farimarwat.saferequest.HttpException
import com.farimarwat.saferequest.NetworkException
import com.farimarwat.saferequest.ProtectionLevel
import com.farimarwat.saferequest.SafeRequest
import com.farimarwat.saferequest.SecurityBlockedException
import com.farimarwat.saferequestexample.ui.theme.SafeRequestExampleTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SafeRequestExampleTheme {
                UsersScreen()
            }
        }
    }
}

/** The protection level of the users screen. For testing, change it here (LOW, MEDIUM, HIGH). */
private val USERS_LEVEL = ProtectionLevel.LOW

/** One user from JSONPlaceholder (a free fake JSON API); unknown keys in the answer are ignored. */
@Serializable
data class User(
    val id: Int,
    val name: String,
    val username: String,
    val email: String,
    val phone: String = "",
    val website: String = "",
    val address: Address? = null,
    val company: Company? = null,
)

@Serializable
data class Address(val city: String = "")

@Serializable
data class Company(val name: String = "")

/**
 * The app's one client for the users API, made once and kept (it holds the connections). In a real
 * app this lives in your repository or DI graph (made once, with the Application as its Context).
 */
private object UsersApi {
    @Volatile
    private var client: SafeRequest? = null

    fun get(context: Context): SafeRequest = client ?: synchronized(this) {
        client ?: create(context).also { client = it }
    }
}

private fun create(context: Context): SafeRequest =
    SafeRequest(context, "https://jsonplaceholder.typicode.com/") {
        protection = USERS_LEVEL
        // Debug builds only: your own debug key (filled in by app/build.gradle.kts), so Android
        // Studio's tools don't block requests. Empty in release builds: every check stays strict.
        if (BuildConfig.DEBUG_KEY_SHA256.isNotEmpty()) debugSignatures(BuildConfig.DEBUG_KEY_SHA256)
        // Release builds: the key you sign with, so an edited, re-signed copy of the app is blocked.
        if (BuildConfig.RELEASE_KEY_SHA256.isNotEmpty()) expectSignatures(BuildConfig.RELEASE_KEY_SHA256)
        header("Accept", "application/json")
    }

/** Lists the users of the fake JSON API, decoded into [User]s. */
suspend fun listUsers(context: Context): List<User> = UsersApi.get(context).get("users").body()

/** What the users screen shows. */
private sealed interface UsersState {
    data object Loading : UsersState
    data class Loaded(val users: List<User>) : UsersState
    data class Failed(val title: String, val detail: String) : UsersState
}

/** A failure in words a user understands. */
private fun failureOf(e: Exception): UsersState.Failed = when (e) {
    is SecurityBlockedException -> UsersState.Failed(
        "Connection blocked",
        "For your safety, this app can't connect on this device or network.",
    )
    is NetworkException -> UsersState.Failed("No connection", "Check your internet connection and try again.")
    is HttpException -> UsersState.Failed("Server problem", "The server answered with error ${e.code}. Try again later.")
    else -> UsersState.Failed("Something went wrong", "Please try again.")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsersScreen() {
    var state by remember { mutableStateOf<UsersState>(UsersState.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    suspend fun load() {
        state = try {
            UsersState.Loaded(listUsers(context))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failureOf(e)
        }
    }
    // One load at a time: a new one (pull to refresh, Try again) replaces the one running.
    fun reload(pulled: Boolean) {
        loading?.cancel()
        loading = scope.launch {
            refreshing = pulled  // the pull indicator only for a pull; the first load shows the spinner
            try {
                load()
            } finally {
                refreshing = false
            }
        }
    }
    LaunchedEffect(Unit) { reload(pulled = false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Contacts", fontWeight = FontWeight.SemiBold)
                        val count = (state as? UsersState.Loaded)?.users?.size
                        Text(
                            when {
                                count != null -> "$count people"
                                state is UsersState.Failed -> "Not loaded"
                                else -> "Loading…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { reload(pulled = true) },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (val current = state) {
                UsersState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is UsersState.Failed -> FailedView(current, onRetry = {
                    state = UsersState.Loading
                    reload(pulled = false)
                })
                is UsersState.Loaded -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(current.users, key = { it.id }) { UserCard(it) }
                }
            }
        }
    }
}

@Composable
private fun UserCard(user: User) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(user)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(user.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("@${user.username}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Text(user.email.lowercase(), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val place = listOfNotNull(user.company?.name?.takeIf { it.isNotBlank() }, user.address?.city?.takeIf { it.isNotBlank() })
                if (place.isNotEmpty()) {
                    Text(place.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A round avatar with the user's initials, in a colour picked from their id. */
@Composable
private fun Avatar(user: User) {
    val colors = listOf(0xFF5B6CFF, 0xFF00A884, 0xFFFF7A45, 0xFFB05CFF, 0xFF0096C7, 0xFFE0457B)
    val initials = user.name.split(' ').filter { it.firstOrNull()?.isLetter() == true && !it.endsWith('.') }
        .take(2).joinToString("") { it.first().uppercase() }
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(colors[user.id.mod(colors.size)])),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FailedView(failure: UsersState.Failed, onRetry: () -> Unit) {
    // Scrollable, so pull-to-refresh also works on this screen.
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(120.dp))
        Text(failure.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(failure.detail, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}
