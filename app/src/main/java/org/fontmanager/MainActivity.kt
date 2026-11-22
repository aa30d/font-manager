package org.fontmanager

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.text.Layout
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    private val library: FontLibrary by viewModels()
    private val openFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { library.importFiles(it) }
    private val openFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(library::addSource) }
    private val createPng = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { it?.let(library::export) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent { FontApp(library, { openFiles.launch(arrayOf("*/*")) }, { openFolder.launch(null) }, { createPng.launch("font-preview.png") }) }
        if (savedInstanceState == null) handleIntent(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList() ?: emptyList()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) library.importFiles(uris)
    }
}

@Composable
private fun FontApp(library: FontLibrary, openFiles: () -> Unit, openFolder: () -> Unit, export: () -> Unit) {
    val light = lightColorScheme(primary = Color(0xFF5145A4), background = Color.White, surface = Color.White, onBackground = Color.Black, onSurface = Color.Black)
    val dark = darkColorScheme(primary = Color(0xFFC5BCFF), background = Color.Black, surface = Color.Black, onBackground = Color.White, onSurface = Color.White)
    MaterialTheme(colorScheme = if (library.dark) dark else light) {
        if (library.selected == null) LibraryScreen(library, openFiles, openFolder) else PreviewScreen(library, export)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(library: FontLibrary, openFiles: () -> Unit, openFolder: () -> Unit) {
    var showSources by remember { mutableStateOf(false) }
    val filtered = remember(library.query, library.fonts.size) {
        val query = library.query.trim()
        library.fonts.filter { it.name.contains(query, true) || it.source.contains(query, true) }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("کتابخانه فونت", fontWeight = FontWeight.Bold); Text("فونت‌های شما، یک‌جا", style = MaterialTheme.typography.labelMedium) } }, actions = {
            IconButton(onClick = library::toggleTheme) { Icon(if (library.dark) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = "تغییر تم") }
        }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = openFiles, icon = { Icon(Icons.Default.Add, null) }, text = { Text("باز کردن فونت") }) }
    ) { padding ->
        LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = library.query, onValueChange = { library.query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, label = { Text("جست‌وجوی فونت یا منبع") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = openFolder, modifier = Modifier.weight(1f)) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text("افزودن پوشه", maxLines = 1) }
                        FilledTonalButton(onClick = { showSources = !showSources }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Folder, null); Spacer(Modifier.width(6.dp)); Text("پوشه‌ها (${library.sources.size})", maxLines = 1) }
                    }
                    if (showSources) {
                        if (library.sources.isEmpty()) Text("هنوز پوشه‌ای انتخاب نشده است.", style = MaterialTheme.typography.bodyMedium)
                        library.sources.forEach { source ->
                            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(source.uri.toString(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(source.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("جست‌وجوی عمیق", modifier = Modifier.weight(1f)); Switch(checked = source.deep, onCheckedChange = { library.setDeep(source.uri, it) })
                                        IconButton(onClick = { library.removeSource(source.uri) }) { Icon(Icons.Default.DeleteOutline, contentDescription = "حذف پوشه") }
                                    }
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("فونت‌ها", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(library.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (library.scanning) IconButton(onClick = library::cancelScan) { Icon(Icons.Default.Close, "توقف بررسی") }
                        else IconButton(onClick = library::refresh) { Icon(Icons.Default.Refresh, "تازه‌سازی") }
                    }
                    if (library.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("${filtered.size} مورد", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(if (library.scanning) "در انتظار نتایج…" else "فونتی یافت نشد. فایل یا پوشه‌ای اضافه کنید.", modifier = Modifier.padding(vertical = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(filtered) { entry ->
                ElevatedCard(onClick = { library.open(entry) }, modifier = Modifier.fillMaxWidth().height(170.dp)) {
                    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text("Aa ا", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary) }
                        Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.info.format, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(entry.source, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewScreen(library: FontLibrary, export: () -> Unit) {
    val entry = library.selected ?: return
    BackHandler { library.back() }
    Scaffold(topBar = { TopAppBar(title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { IconButton(onClick = library::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("${entry.info.format} · ${entry.source}", style = MaterialTheme.typography.bodySmall)
            entry.info.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            if (library.opening) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!library.opening && library.face == null) Text(if (entry.info.format in listOf("WOFF", "WOFF2")) "پیش‌نمایش این فرمت هنوز پشتیبانی نمی‌شود." else library.status)
            if (library.face != null) {
                OutlinedTextField(value = library.text, onValueChange = { library.text = it }, label = { Text("متن پیش‌نمایش") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                ElevatedCard(Modifier.fillMaxWidth()) {
                    AndroidView(factory = { TextView(it).apply { setPadding(24, 32, 24, 32) } }, modifier = Modifier.fillMaxWidth(), update = { view ->
                        view.typeface = library.face; view.text = library.text; view.textSize = library.size
                        view.setTextColor(if (library.dark) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                        view.textDirection = when (library.direction) { 1 -> View.TEXT_DIRECTION_RTL; 2 -> View.TEXT_DIRECTION_LTR; else -> View.TEXT_DIRECTION_FIRST_STRONG }
                        view.gravity = when (library.alignment) { 0 -> Gravity.LEFT; 2 -> Gravity.RIGHT; else -> Gravity.CENTER_HORIZONTAL }
                        view.justificationMode = if (library.alignment == 3) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE
                    })
                }
                Text("اندازه متن: ${library.size.toInt()}")
                Slider(value = library.size, onValueChange = { library.size = it }, valueRange = 12f..132f)
                ChoiceRow("جهت متن", listOf("خودکار", "راست به چپ", "چپ به راست"), library.direction) { library.direction = it }
                ChoiceRow("هم‌ترازی", listOf("چپ", "وسط", "راست", "دوطرفه"), library.alignment) { library.alignment = it }
                Button(onClick = export, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("ذخیره PNG شفاف · ۱۰۸۰ × ۱۰۸۰") }
            }
            Text("جدول‌ها: ${entry.info.tables.sorted().joinToString().ifEmpty { "قابل‌خواندن نیست" }}", style = MaterialTheme.typography.bodySmall)
            Text("گلیف‌ها، SVG، محورهای متغیر و ویژگی‌های OpenType هنوز در دسترس نیستند.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(library.status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ChoiceRow(title: String, items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEachIndexed { index, item -> FilterChip(selected = selected == index, onClick = { onSelect(index) }, label = { Text(item, maxLines = 1, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.weight(1f)) }
        }
    }
}
