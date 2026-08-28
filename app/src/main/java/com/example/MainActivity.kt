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

import androidx.compose.material.icons.filled.SystemUpdate
import com.example.update.AppUpdateManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = DefaultAppContainer
        setContent {
            MyApplicationTheme {
                val viewModel: CallViewModel = viewModel(
                    factory = CallViewModelFactory(
                        appContainer.getRepository(this),
                        appContainer.geminiRepository,
                        appContainer.gitHubUpdateRepository
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
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()

    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val isCheckingUpdate by viewModel.isCheckingUpdate.collectAsStateWithLifecycle()
    val isDownloadingUpdate by viewModel.isDownloadingUpdate.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val updateStatusMessage by viewModel.updateStatusMessage.collectAsStateWithLifecycle()

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
                // Some custom storage providers do not support persistable permissions
            } catch (_: Exception) {
                // Ignore fallback
            }
            viewModel.syncFolder(context, uri)
        }
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
                            Text(
                                text = "Syncing & Analyzing Calls...",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = syncStatus,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
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
                contentPadding = PaddingValues(bottom = 88.dp) // Space for FAB
            ) {
                items(recordings, key = { it.id }) { recording ->
                    RecordingCard(
                        recording = recording,
                        onPlayAudio = { viewModel.playAudio(context, recording) },
                        onDelete = { recordingToDelete = recording },
                        onAddToCalendar = { viewModel.syncToCalendar(context, recording) },
                        onShare = {
                            try {
                                val sendIntent: Intent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Call Summary for ${recording.title}:\n\n${recording.decodedSummary}\n\nTranscription:\n${recording.decodedTranscription}"
                                    )
                                    type = "text/plain"
                                }
                                val shareIntent = Intent.createChooser(sendIntent, "Share Call Summary")
                                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(shareIntent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Cannot open sharing dialog.", Toast.LENGTH_SHORT).show()
                            }
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
    onPlayAudio: () -> Unit,
    onDelete: () -> Unit,
    onAddToCalendar: () -> Unit,
    onShare: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy - HH:mm", Locale.getDefault()) }

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = recording.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = dateFormat.format(Date(recording.timestamp)),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.DarkGray
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onPlayAudio,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape) // Green
                                .border(2.dp, Color.Black, CircleShape)
                                .size(36.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play Audio", tint = Color.Black, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = onShare,
                            modifier = Modifier
                                .background(Color.White, CircleShape)
                                .border(2.dp, Color.Black, CircleShape)
                                .size(36.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.Black, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = onAddToCalendar,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary, CircleShape) // Yellow
                                .border(2.dp, Color.Black, CircleShape)
                                .size(36.dp)
                        ) {
                            Icon(Icons.Default.CalendarToday, contentDescription = "Add to Calendar", tint = Color.Black, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.secondary, CircleShape) // Pink
                                .border(2.dp, Color.Black, CircleShape)
                                .size(36.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Black, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), // Cyan
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, Color.Black, RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "SUMMARY & ACTION ITEMS",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.Black,
                            fontWeight = FontWeight.Black
                        )
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
                    IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Toggle Transcription",
                            tint = Color.Black
                        )
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
