package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
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
                        appContainer.geminiRepository
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

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.syncFolder(context, uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CallScribe", fontWeight = FontWeight.Black, color = Color.Black) },
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
            if (recordings.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Analyzed Calls",
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
                    .padding(16.dp)
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
                    placeholder = { Text("Search...", fontWeight = FontWeight.Bold, color = Color.DarkGray) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Black) },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Black,
                        unfocusedBorderColor = Color.Black,
                        focusedContainerColor = MaterialTheme.colorScheme.surface, // Brutal White
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
                                text = "Syncing with Database...",
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
                            text = "No Calls Found",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap the folder icon to select a directory with audio recordings to analyze.",
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
                        onDelete = { viewModel.deleteRecording(recording.id) },
                        onAddToCalendar = { viewModel.syncToCalendar(context, recording) },
                        onShare = {
                             val sendIntent: Intent = Intent().apply {
                                 action = Intent.ACTION_SEND
                                 putExtra(Intent.EXTRA_TEXT, "Call Summary for ${recording.title}:\n\n${recording.decodedSummary}\n\nTranscription:\n${recording.decodedTranscription}")
                                 type = "text/plain"
                             }
                             val shareIntent = Intent.createChooser(sendIntent, null)
                             context.startActivity(shareIntent)
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
    onDelete: () -> Unit,
    onAddToCalendar: () -> Unit,
    onShare: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val dateFormat = SimpleDateFormat("MMM dd, yyyy - HH:mm", Locale.getDefault())
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background), // White
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = recording.title,
                            style = MaterialTheme.typography.titleLarge,
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
                    Row {
                        IconButton(
                            onClick = onShare,
                            modifier = Modifier
                                .background(Color.White, CircleShape)
                                .border(2.dp, Color.Black, CircleShape)
                                .size(40.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.Black, modifier = Modifier.padding(4.dp).size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = onAddToCalendar,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary, CircleShape) // Yellow
                                .border(2.dp, Color.Black, CircleShape)
                                .size(40.dp)
                        ) {
                            Icon(Icons.Default.CalendarToday, contentDescription = "Add to Calendar", tint = Color.Black, modifier = Modifier.padding(4.dp).size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.secondary, CircleShape) // Pink
                                .border(2.dp, Color.Black, CircleShape)
                                .size(40.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Black, modifier = Modifier.padding(4.dp).size(20.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), // Cyan
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, Color.Black, RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "SUMMARY",
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
