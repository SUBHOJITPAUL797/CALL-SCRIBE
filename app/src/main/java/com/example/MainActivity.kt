package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.Recording
import com.example.di.DefaultAppContainer
import com.example.ui.CallViewModel
import com.example.ui.CallViewModelFactory
import com.example.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import com.example.data.CallMetadataParser
import com.example.ui.ChatMessage
import com.example.ui.MessageSender
import com.example.update.AppUpdateManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = DefaultAppContainer
        val apiKeyManager = appContainer.getApiKeyManager(this)
        val geminiRepository = appContainer.getGeminiRepository(this)
        val nvidiaRepository = appContainer.getNvidiaRepository(this)
        setContent {
            MyApplicationTheme {
                val viewModel: CallViewModel = viewModel(
                    factory = CallViewModelFactory(
                        appContainer.getRepository(this),
                        geminiRepository,
                        appContainer.gitHubUpdateRepository,
                        apiKeyManager,
                        nvidiaRepository
                    )
                )

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CallScribeApp(viewModel = viewModel)
                }
            }
        }
    }
}

// Brutal Modifier to mimic solid offset drop shadows
fun Modifier.brutalShadow(
    offsetX: Int = 4,
    offsetY: Int = 4,
    color: Color = Color.Black,
    cornerRadius: Int = 16
): Modifier = this.then(
    Modifier.padding(end = offsetX.dp, bottom = offsetY.dp)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallScribeApp(viewModel: CallViewModel) {
    val context = LocalContext.current
    val clipboard: ClipboardManager = LocalClipboardManager.current
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val syncProcessedCount by viewModel.syncProcessedCount.collectAsStateWithLifecycle()
    val syncTotalCount by viewModel.syncTotalCount.collectAsStateWithLifecycle()
    val syncErrorCount by viewModel.syncErrorCount.collectAsStateWithLifecycle()

    val showApiKeyDialog by viewModel.showApiKeyDialog.collectAsStateWithLifecycle()
    val selectedFolderForLimit by viewModel.selectedFolderForLimit.collectAsStateWithLifecycle()
    val folderTotalRecordings by viewModel.folderTotalRecordings.collectAsStateWithLifecycle()

    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val isCheckingUpdate by viewModel.isCheckingUpdate.collectAsStateWithLifecycle()
    val isDownloadingUpdate by viewModel.isDownloadingUpdate.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val updateStatusMessage by viewModel.updateStatusMessage.collectAsStateWithLifecycle()

    // Audio Player state
    val playingRecordingId by viewModel.audioPlayer.playingRecordingId.collectAsStateWithLifecycle()
    val isPlaying by viewModel.audioPlayer.isPlaying.collectAsStateWithLifecycle()
    val currentPositionMs by viewModel.audioPlayer.currentPositionMs.collectAsStateWithLifecycle()
    val durationMs by viewModel.audioPlayer.durationMs.collectAsStateWithLifecycle()

    // Chat with Call state
    val activeChatRecording by viewModel.activeChatRecording.collectAsStateWithLifecycle()
    val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val isChatLoading by viewModel.isChatLoading.collectAsStateWithLifecycle()

    var enteredApiKey by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var apiKeyTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isTestingKey by remember { mutableStateOf(false) }

    var enteredNvidiaKey by remember { mutableStateOf("") }
    var nvidiaKeyVisible by remember { mutableStateOf(false) }
    var nvidiaKeyTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isTestingNvidiaKey by remember { mutableStateOf(false) }

    LaunchedEffect(showApiKeyDialog) {
        if (showApiKeyDialog) {
            enteredApiKey = viewModel.getApiKey()
            enteredNvidiaKey = viewModel.getNvidiaApiKey()
            apiKeyTestResult = null
            nvidiaKeyTestResult = null
            isTestingKey = false
            isTestingNvidiaKey = false
        }
    }

    LaunchedEffect(updateStatusMessage) {
        updateStatusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearUpdateStatusMessage()
        }
    }

    var recordingToDelete by remember { mutableStateOf<Recording?>(null) }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
            viewModel.onFolderSelected(context, uri)
        }
    }

    // Chat with Call Dialog (BottomSheet-style full dialog)
    if (activeChatRecording != null) {
        val chatRec = activeChatRecording!!
        var chatInput by remember { mutableStateOf("") }
        val quickChips = listOf(
            "What are the action items?",
            "What was agreed upon?",
            "Were any dates or deadlines mentioned?",
            "Give me a 2-bullet email summary"
        )

        AlertDialog(
            onDismissRequest = { viewModel.closeChat() },
            title = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = Color.Black)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Chat with Call",
                            fontWeight = FontWeight.Black,
                            color = Color.Black,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Text(
                        text = CallMetadataParser.cleanCallTitle(chatRec.title),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!viewModel.isApiKeyConfigured()) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF22C55E), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("On-Device AI (No Key Required)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF22C55E), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Chat messages
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .border(2.dp, Color.Black, RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    ) {
                        LazyColumn(
                            modifier = Modifier.padding(10.dp),
                            reverseLayout = false
                        ) {
                            items(chatMessages) { msg ->
                                val isUser = msg.sender == MessageSender.USER
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .widthIn(max = 260.dp)
                                            .background(
                                                if (isUser) MaterialTheme.colorScheme.primary else Color.White,
                                                RoundedCornerShape(
                                                    topStart = 12.dp, topEnd = 12.dp,
                                                    bottomStart = if (isUser) 12.dp else 4.dp,
                                                    bottomEnd = if (isUser) 4.dp else 12.dp
                                                )
                                            )
                                            .border(
                                                2.dp, Color.Black,
                                                RoundedCornerShape(
                                                    topStart = 12.dp, topEnd = 12.dp,
                                                    bottomStart = if (isUser) 12.dp else 4.dp,
                                                    bottomEnd = if (isUser) 4.dp else 12.dp
                                                )
                                            )
                                            .padding(10.dp)
                                    ) {
                                        Text(msg.text, style = MaterialTheme.typography.bodySmall, color = Color.Black, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                            if (isChatLoading) {
                                item {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                        Box(
                                            modifier = Modifier
                                                .background(Color.White, RoundedCornerShape(12.dp))
                                                .border(2.dp, Color.Black, RoundedCornerShape(12.dp))
                                                .padding(10.dp)
                                        ) {
                                            Text("Thinking...", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Quick prompt chips
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(quickChips.size) { i ->
                            SuggestionChip(
                                onClick = {
                                    if (!isChatLoading) viewModel.sendChatMessage(quickChips[i])
                                },
                                label = {
                                    Text(quickChips[i], style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                },
                                border = SuggestionChipDefaults.suggestionChipBorder(enabled = true, borderColor = Color.Black, borderWidth = 1.5.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Chat input
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = chatInput,
                            onValueChange = { chatInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Ask anything about this call...", style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (chatInput.isNotBlank() && !isChatLoading) {
                                    viewModel.sendChatMessage(chatInput)
                                    chatInput = ""
                                }
                            }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Black,
                                unfocusedBorderColor = Color.Black
                            )
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(
                            onClick = {
                                if (chatInput.isNotBlank() && !isChatLoading) {
                                    viewModel.sendChatMessage(chatInput)
                                    chatInput = ""
                                }
                            },
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                .border(2.dp, Color.Black, RoundedCornerShape(8.dp))
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.Black)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.closeChat() },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(2.dp, Color.Black)
                ) {
                    Text("Close", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.border(3.dp, Color.Black, RoundedCornerShape(16.dp))
        )
    }

    // API Key Dialog
    if (showApiKeyDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissApiKeyDialog() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("AI API Keys", fontWeight = FontWeight.Black, color = Color.Black)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {

                    // ── Gemini Section ───────────────────────────────────────
                    Text("🤖 Gemini API Key (Primary)", fontWeight = FontWeight.Black, color = Color.Black, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Best quality — transcription + summary + chat in one step.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = enteredApiKey,
                        onValueChange = { enteredApiKey = it; apiKeyTestResult = null },
                        label = { Text("Gemini Key (AIzaSy...)", fontWeight = FontWeight.Bold) },
                        placeholder = { Text("Paste AIzaSy... key") },
                        singleLine = true,
                        visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    imageVector = if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle visibility",
                                    tint = Color.Black
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    apiKeyTestResult?.let { (success, msg) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (success) Icons.Default.CheckCircle else Icons.Default.Error,
                                null,
                                tint = if (success) Color(0xFF22C55E) else Color(0xFFEF4444),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(msg, style = MaterialTheme.typography.labelSmall, color = if (success) Color(0xFF22C55E) else Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedButton(
                            onClick = {
                                if (enteredApiKey.isNotBlank() && !isTestingKey) {
                                    isTestingKey = true; apiKeyTestResult = null
                                    viewModel.testApiKey(enteredApiKey) { ok, msg -> apiKeyTestResult = Pair(ok, msg); isTestingKey = false }
                                }
                            },
                            enabled = enteredApiKey.isNotBlank() && !isTestingKey,
                            shape = RoundedCornerShape(8.dp), border = BorderStroke(2.dp, Color.Black)
                        ) {
                            if (isTestingKey) { CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = Color.Black); Spacer(Modifier.width(4.dp)) }
                            Text(if (isTestingKey) "Testing..." else "Test", fontWeight = FontWeight.Bold, color = Color.Black, style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Exception) {}
                        }) { Text("Get Free Key →", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                    }

                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.Divider(color = Color.LightGray)
                    Spacer(Modifier.height(12.dp))

                    // ── NVIDIA Section ───────────────────────────────────────
                    Text("⚡ NVIDIA API Key (Fallback)", fontWeight = FontWeight.Black, color = Color.Black, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Used automatically when Gemini hits its free quota. Llama 3.1 70B + Canary ASR.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = enteredNvidiaKey,
                        onValueChange = { enteredNvidiaKey = it; nvidiaKeyTestResult = null },
                        label = { Text("NVIDIA NIM Key (nvapi-...)", fontWeight = FontWeight.Bold) },
                        placeholder = { Text("Paste nvapi-... key") },
                        singleLine = true,
                        visualTransformation = if (nvidiaKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { nvidiaKeyVisible = !nvidiaKeyVisible }) {
                                Icon(
                                    imageVector = if (nvidiaKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle visibility",
                                    tint = Color.Black
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    nvidiaKeyTestResult?.let { (success, msg) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (success) Icons.Default.CheckCircle else Icons.Default.Error,
                                null,
                                tint = if (success) Color(0xFF22C55E) else Color(0xFFEF4444),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(msg, style = MaterialTheme.typography.labelSmall, color = if (success) Color(0xFF22C55E) else Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedButton(
                            onClick = {
                                if (enteredNvidiaKey.isNotBlank() && !isTestingNvidiaKey) {
                                    isTestingNvidiaKey = true; nvidiaKeyTestResult = null
                                    viewModel.testNvidiaApiKey(enteredNvidiaKey) { ok, msg -> nvidiaKeyTestResult = Pair(ok, msg); isTestingNvidiaKey = false }
                                }
                            },
                            enabled = enteredNvidiaKey.isNotBlank() && !isTestingNvidiaKey,
                            shape = RoundedCornerShape(8.dp), border = BorderStroke(2.dp, Color(0xFF76B900))
                        ) {
                            if (isTestingNvidiaKey) { CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = Color(0xFF76B900)); Spacer(Modifier.width(4.dp)) }
                            Text(if (isTestingNvidiaKey) "Testing..." else "Test", fontWeight = FontWeight.Bold, color = Color(0xFF76B900), style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://build.nvidia.com")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Exception) {}
                        }) { Text("Get Free Key →", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF76B900)) }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF22C55E), modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("App works offline with no keys (On-Device AI)", style = MaterialTheme.typography.labelSmall, color = Color(0xFF22C55E), fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveApiKey(enteredApiKey)
                        viewModel.saveNvidiaApiKey(enteredNvidiaKey)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(2.dp, Color.Black)
                ) {
                    Text("Save Keys", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.dismissApiKeyDialog() },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(2.dp, Color.Black)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.border(3.dp, Color.Black, RoundedCornerShape(16.dp))
        )
    }

    // Sync Batch Options Dialog
    if (selectedFolderForLimit != null) {
        val totalCount = folderTotalRecordings
        AlertDialog(
            onDismissRequest = { viewModel.dismissLimitDialog() },
            title = {
                Text("Analyze Recordings", fontWeight = FontWeight.Black, color = Color.Black)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Found $totalCount call recording(s) in selected folder. Choose how many recent calls to analyze:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color.DarkGray
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.startSyncWithLimit(context, 20) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(2.dp, Color.Black)
                    ) {
                        Text("Latest 20 Calls (Fastest - ~1 min)", fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.startSyncWithLimit(context, 50) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(2.dp, Color.Black)
                    ) {
                        Text("Latest 50 Calls (Recommended)", fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.startSyncWithLimit(context, 100) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(2.dp, Color.Black)
                    ) {
                        Text("Latest 100 Calls", fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                    if (totalCount > 100) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.startSyncWithLimit(context, totalCount) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(2.dp, Color.Black)
                        ) {
                            Text("All $totalCount Calls (May take long)", fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.dismissLimitDialog() },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(2.dp, Color.Black)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.border(3.dp, Color.Black, RoundedCornerShape(16.dp))
        )
    }

    if (recordingToDelete != null) {
        AlertDialog(
            onDismissRequest = { recordingToDelete = null },
            title = {
                Text(
                    text = "Delete Recording?",
                    fontWeight = FontWeight.Black,
                    color = Color.Black
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete '${recordingToDelete?.title}'? This cannot be undone.",
                    fontWeight = FontWeight.Medium,
                    color = Color.DarkGray
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        recordingToDelete?.id?.let { viewModel.deleteRecording(it) }
                        recordingToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(2.dp, Color.Black)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { recordingToDelete = null },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(2.dp, Color.Black)
                ) {
                    Text("Cancel", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.border(3.dp, Color.Black, RoundedCornerShape(16.dp))
        )
    }

    if (updateInfo != null) {
        val info = updateInfo!!
        AlertDialog(
            onDismissRequest = {
                if (!isDownloadingUpdate) viewModel.dismissUpdateDialog()
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Update Available: ${info.latestVersionName}",
                        fontWeight = FontWeight.Black,
                        color = Color.Black
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = info.releaseTitle,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "RELEASE NOTES",
                        fontWeight = FontWeight.Black,
                        color = Color.DarkGray,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .border(1.dp, Color.Black, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = info.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Black
                        )
                    }

                    if (isDownloadingUpdate) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Downloading Update: ${(downloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .border(2.dp, Color.Black),
                            color = MaterialTheme.colorScheme.secondary,
                            trackColor = Color.White
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.downloadAndInstallUpdate(context)
                    },
                    enabled = !isDownloadingUpdate,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(2.dp, Color.Black)
                ) {
                    Text(
                        text = if (isDownloadingUpdate) "Downloading..." else "Update Now",
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            },
            dismissButton = {
                Row {
                    OutlinedButton(
                        onClick = {
                            AppUpdateManager.openBrowserReleasePage(context, info.releasePageUrl)
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(2.dp, Color.Black),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White)
                    ) {
                        Text("GitHub", fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = { viewModel.skipThisUpdate() },
                        enabled = !isDownloadingUpdate,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(2.dp, Color(0xFF888888))
                    ) {
                        Text("Skip", color = Color(0xFF888888))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = { viewModel.dismissUpdateDialog() },
                        enabled = !isDownloadingUpdate,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(2.dp, Color.Black)
                    ) {
                        Text("Later", fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.border(3.dp, Color.Black, RoundedCornerShape(16.dp))
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Call Scribe", fontWeight = FontWeight.Black, color = Color.Black) },
                actions = {
                    IconButton(
                        onClick = { viewModel.showApiKeyDialog.value = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "Gemini API Key Settings",
                            tint = Color.Black
                        )
                    }
                    IconButton(
                        onClick = { viewModel.checkForUpdates(manual = true) },
                        enabled = !isCheckingUpdate
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.Black
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = "Check for Updates",
                                tint = Color.Black
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary, // Yellow
                ),
                modifier = Modifier.border(BorderStroke(3.dp, Color.Black))
            )
        },
        floatingActionButton = {
            Box(
                modifier = Modifier
                    .padding(end = 4.dp, bottom = 4.dp)
            ) {
                // Fake shadow box
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .offset(x = 6.dp, y = 6.dp)
                        .background(Color.Black, RoundedCornerShape(16.dp))
                )
                FloatingActionButton(
                    onClick = { folderLauncher.launch(null) },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer, // Green
                    contentColor = Color.Black,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.border(3.dp, Color.Black, RoundedCornerShape(16.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Select Folder",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            // Dashboard info
            if (recordings.isNotEmpty() || searchQuery.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (searchQuery.isBlank()) "Analyzed Calls" else "Search Results",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = Color.Black
                    )
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                            .border(2.dp, Color.Black, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${recordings.size}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Black,
                            color = Color.Black
                        )
                    }
                }
            }
            
            // Search Bar Component
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Shadow
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .offset(x = 4.dp, y = 4.dp)
                        .background(Color.Black, RoundedCornerShape(12.dp))
                )
                // Field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::updateSearchQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                    placeholder = { Text("Search calls, summaries, topics...", fontWeight = FontWeight.Bold, color = Color.DarkGray) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Black) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search", tint = Color.Black)
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Black,
                        unfocusedBorderColor = Color.Black,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black
                    ),
                    textStyle = LocalTextStyle.current.copy(fontWeight = FontWeight.Bold)
                )
            }

            AnimatedVisibility(visible = isSyncing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .offset(x = 4.dp, y = 4.dp)
                            .background(Color.Black, RoundedCornerShape(16.dp))
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(3.dp, Color.Black, RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), // Cyan
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Syncing & Analyzing...",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Black,
                                    color = Color.Black
                                )
                                Button(
                                    onClick = { viewModel.cancelSync() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(2.dp, Color.Black),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Stop", fontWeight = FontWeight.Black, color = Color.Black, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = syncStatus,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            if (syncTotalCount > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Analyzed: $syncProcessedCount / $syncTotalCount" + if (syncErrorCount > 0) " (${syncErrorCount} failed)" else "",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.DarkGray
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { if (syncTotalCount > 0) syncProgress else 0f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .border(2.dp, Color.Black),
                                color = MaterialTheme.colorScheme.secondary,
                                trackColor = Color.White
                            )
                        }
                    }
                }
            }
            
            if (!isSyncing && recordings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
                            .border(3.dp, Color.Black, RoundedCornerShape(24.dp))
                            .padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isBlank()) "No Calls Found" else "No Matching Calls",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (searchQuery.isBlank())
                                "Tap the green folder button to select a directory with call recordings (.mp3, .m4a, .wav) to analyze."
                            else
                                "No transcripts or summaries matched '$searchQuery'. Try another query.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.DarkGray,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                items(recordings, key = { it.id }) { recording ->
                    RecordingCard(
                        recording = recording,
                        isCurrentlyPlaying = playingRecordingId == recording.id && isPlaying,
                        isAudioLoaded = playingRecordingId == recording.id,
                        currentPositionMs = if (playingRecordingId == recording.id) currentPositionMs else 0,
                        durationMs = if (playingRecordingId == recording.id) durationMs else 0,
                        onTogglePlay = { viewModel.toggleAudioPlay(context, recording) },
                        onSeek = { pos -> viewModel.seekAudio(pos) },
                        onChat = { viewModel.openChat(recording) },
                        onDelete = { recordingToDelete = recording },
                        onAddToCalendar = { viewModel.syncToCalendar(context, recording) },
                        onShare = {
                            try {
                                val sendIntent: Intent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "📋 Call Summary for ${CallMetadataParser.cleanCallTitle(recording.title)}:\n\n${recording.decodedSummary}\n\n--- Transcription ---\n${recording.decodedTranscription}"
                                    )
                                    type = "text/plain"
                                }
                                val shareIntent = Intent.createChooser(sendIntent, "Share Call Summary")
                                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(shareIntent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Cannot open sharing dialog.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onCopySummary = {
                            clipboard.setText(AnnotatedString(recording.decodedSummary))
                            Toast.makeText(context, "Summary copied!", Toast.LENGTH_SHORT).show()
                        },
                        onCopyTranscript = {
                            clipboard.setText(AnnotatedString(recording.decodedTranscription))
                            Toast.makeText(context, "Transcript copied!", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun RecordingCard(
    recording: Recording,
    isCurrentlyPlaying: Boolean,
    isAudioLoaded: Boolean,
    currentPositionMs: Int,
    durationMs: Int,
    onTogglePlay: () -> Unit,
    onSeek: (Int) -> Unit,
    onChat: () -> Unit,
    onDelete: () -> Unit,
    onAddToCalendar: () -> Unit,
    onShare: () -> Unit,
    onCopySummary: () -> Unit,
    onCopyTranscript: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy - HH:mm", Locale.getDefault()) }
    val metadata = remember(recording.title) { CallMetadataParser.parse(recording.title) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .animateContentSize()
    ) {
        // Drop Shadow
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 6.dp, y = 6.dp)
                .background(Color.Black, RoundedCornerShape(24.dp))
        )
        // Main Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(3.dp, Color.Black, RoundedCornerShape(24.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Title + Action Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = metadata.cleanTitle.ifBlank { recording.title },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = dateFormat.format(Date(recording.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.DarkGray
                            )
                            metadata.contactOrNumber?.let {
                                Text("•", style = MaterialTheme.typography.labelSmall, color = Color.DarkGray)
                                Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF6366F1))
                            }
                            when (metadata.direction) {
                                com.example.data.CallDirection.INCOMING -> Text("↙ In", style = MaterialTheme.typography.labelSmall, color = Color(0xFF22C55E), fontWeight = FontWeight.Black)
                                com.example.data.CallDirection.OUTGOING -> Text("↗ Out", style = MaterialTheme.typography.labelSmall, color = Color(0xFFF59E0B), fontWeight = FontWeight.Black)
                                else -> {}
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Chat button
                        IconButton(
                            onClick = onChat,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                                .border(2.dp, Color.Black, CircleShape)
                                .size(36.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat with Call", tint = Color.Black, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onShare,
                            modifier = Modifier
                                .background(Color.White, CircleShape)
                                .border(2.dp, Color.Black, CircleShape)
                                .size(36.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.Black, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onAddToCalendar,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .border(2.dp, Color.Black, CircleShape)
                                .size(36.dp)
                        ) {
                            Icon(Icons.Default.CalendarToday, contentDescription = "Add to Calendar", tint = Color.Black, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.secondary, CircleShape)
                                .border(2.dp, Color.Black, CircleShape)
                                .size(36.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Black, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // In-App Audio Player
                if (recording.sourceUri != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .border(2.dp, Color.Black, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onTogglePlay,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                                .border(2.dp, Color.Black, CircleShape)
                                .size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (isCurrentlyPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isCurrentlyPlaying) "Pause" else "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            if (isAudioLoaded && durationMs > 0) {
                                Slider(
                                    value = currentPositionMs.toFloat(),
                                    onValueChange = { onSeek(it.toInt()) },
                                    valueRange = 0f..durationMs.toFloat(),
                                    modifier = Modifier.fillMaxWidth().height(20.dp),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.Black,
                                        activeTrackColor = Color.Black,
                                        inactiveTrackColor = Color.LightGray
                                    )
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .background(Color.LightGray, RoundedCornerShape(3.dp))
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    CallMetadataParser.formatDuration(if (isAudioLoaded) currentPositionMs else 0),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.DarkGray,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    CallMetadataParser.formatDuration(if (isAudioLoaded) durationMs else 0),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.DarkGray,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Summary Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, Color.Black, RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SUMMARY & ACTION ITEMS",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.Black,
                                fontWeight = FontWeight.Black
                            )
                            IconButton(onClick = onCopySummary, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Summary", tint = Color.Black, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = recording.decodedSummary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                // Transcription header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TRANSCRIPTION",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.DarkGray,
                        fontWeight = FontWeight.Black
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onCopyTranscript, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Transcript", tint = Color.DarkGray, modifier = Modifier.size(14.dp))
                        }
                        IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle Transcription",
                                tint = Color.Black
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                AnimatedVisibility(visible = expanded) {
                    Text(
                        text = recording.decodedTranscription,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Black,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (!expanded) {
                    Text(
                        text = recording.decodedTranscription,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Black,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
