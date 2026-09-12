package com.dabhiram.expensetracker.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogViewerScreen() {
    val context = LocalContext.current
    val logFiles by produceState<List<File>>(initialValue = emptyList()) {
        value = loadLogFiles(context)
    }

    var selectedFile by remember { mutableStateOf<File?>(null) }
    var fileContent by remember { mutableStateOf("") }

    LaunchedEffect(selectedFile) {
        fileContent = selectedFile?.readText() ?: ""
    }

    if (selectedFile != null) {
        LogFileDetailScreen(
            fileName = selectedFile!!.name,
            content = fileContent,
            onBack = { selectedFile = null }
        )
    } else {
        LogFileListScreen(
            files = logFiles,
            onFileClick = { selectedFile = it }
        )
    }
}

@Composable
private fun LogFileListScreen(files: List<File>, onFileClick: (File) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Accessibility Log Dumps",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "These files show what GPay and PhonePe expose in their accessibility trees. Use them to tune the transaction parser.",
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No logs yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Make a UPI payment via GPay or PhonePe to generate a log",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn {
                items(files) { file ->
                    ListItem(
                        headlineContent = { Text(file.name) },
                        supportingContent = {
                            val modified = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                                .format(Date(file.lastModified()))
                            Text(
                                "$modified · ${file.length() / 1024} KB",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.Description, contentDescription = null)
                        },
                        modifier = Modifier.clickable { onFileClick(file) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogFileDetailScreen(
    fileName: String,
    content: String,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(fileName, style = MaterialTheme.typography.titleSmall) },
            navigationIcon = {
                TextButton(onClick = onBack) { Text("← Back") }
            }
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                content.ifEmpty { "Empty file" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(bottom = 32.dp)
            )
        }
    }
}

private fun loadLogFiles(context: Context): List<File> {
    val logDir = File(context.getExternalFilesDir(null), "logs")
    if (!logDir.exists()) return emptyList()
    return logDir.listFiles()
        ?.filter { it.isFile && (it.name.startsWith("dump_") || it.name.startsWith("ocr_")) }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()
}
