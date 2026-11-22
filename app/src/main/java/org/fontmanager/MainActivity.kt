package org.fontmanager

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.text.Layout
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import java.util.Locale
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
                                    Text(library.sourceLocation(source.uri), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                        Text(entry.info.names[16]?.firstOrNull() ?: entry.info.names[1]?.firstOrNull() ?: entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.info.format, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(entry.location, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    var tab by remember(entry) { mutableIntStateOf(0) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(entry.info.names[16]?.firstOrNull() ?: entry.info.names[1]?.firstOrNull() ?: entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = library::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") } },
            actions = { IconButton(onClick = export, enabled = library.face != null) { Icon(Icons.Default.Download, "ذخیره PNG") } })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val density = LocalDensity.current
            val maximum = maxHeight.value
            var panelHeight by remember { mutableFloatStateOf(maximum * .32f) }
            val minHeight = minOf(150f, maximum * .45f)
            val maxPanelHeight = maxOf(minHeight, maximum * .70f)
            val actualHeight = panelHeight.coerceIn(minHeight, maxPanelHeight)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (library.opening) CircularProgressIndicator()
                    else if (library.face == null) Text(if (entry.info.format in listOf("WOFF", "WOFF2")) "پیش‌نمایش این فرمت هنوز پشتیبانی نمی‌شود." else library.status, modifier = Modifier.padding(20.dp))
                    else AndroidView(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        factory = { context ->
                            EditText(context).apply {
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                                setText(library.text)
                                setPadding(12, 24, 12, 24)
                                addTextChangedListener(object : TextWatcher {
                                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { library.text = s?.toString() ?: "" }
                                    override fun afterTextChanged(s: Editable?) {}
                                })
                            }
                        },
                        update = { view ->
                            if (view.text.toString() != library.text) { view.setText(library.text); view.setSelection(view.text.length) }
                            if (view.tag !== library.face) { view.typeface = library.face; view.tag = library.face }
                            val variations = library.variationSettings()
                            if (variations.isNotEmpty()) view.setFontVariationSettings(variations)
                            view.fontFeatureSettings = library.featureSettings().ifEmpty { null }
                            view.textSize = library.size
                            view.setLineSpacing(0f, library.lineSpacing)
                            view.setTextColor(if (library.dark) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                            view.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                            view.gravity = (when (library.alignment) { 0 -> Gravity.LEFT; 2 -> Gravity.RIGHT; 3 -> Gravity.LEFT; else -> Gravity.CENTER_HORIZONTAL }) or Gravity.CENTER_VERTICAL
                            view.justificationMode = if (library.alignment == 3) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE
                        }
                    )
                }
                Surface(modifier = Modifier.fillMaxWidth().height(actualHeight.dp), color = MaterialTheme.colorScheme.surfaceContainer, shape = androidx.compose.ui.graphics.RectangleShape, tonalElevation = 2.dp) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(28.dp).pointerInput(minHeight, maxPanelHeight) {
                            detectVerticalDragGestures { change, amount -> change.consume(); panelHeight = (panelHeight.coerceIn(minHeight, maxPanelHeight) - with(density) { amount.toDp().value }).coerceIn(minHeight, maxPanelHeight) }
                        }, contentAlignment = Alignment.Center) {
                            Box(Modifier.width(42.dp).height(4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                        }
                        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                            listOf("Layout", "Variation", "OpenType", "About").forEachIndexed { index, name ->
                                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(name, maxLines = 1) })
                            }
                        }
                        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (tab) {
                                0 -> {
                                    SimpleSlider("اندازه متن", library.size, 12f..132f) { library.size = it }
                                    SimpleSlider("ارتفاع خط", library.lineSpacing, 0.7f..2.5f) { library.lineSpacing = it }
                                    Text("هم‌ترازی", style = MaterialTheme.typography.labelLarge)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf("چپ", "وسط", "راست", "دوطرفه").forEachIndexed { i, name ->
                                            FilterChip(selected = library.alignment == i, onClick = { library.alignment = i }, label = { Text(name, maxLines = 1, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                                1 -> {
                                    if (entry.info.axes.isEmpty()) Text("محور متغیری یافت نشد یا قابل‌خواندن نیست.")
                                    entry.info.axes.forEach { axis ->
                                        val value = library.axisValues[axis.tag] ?: axis.default
                                        SimpleSlider("${axis.name} (${axis.tag})", value, axis.min..axis.max, enabled = axis.min < axis.max) { library.axisValues[axis.tag] = it }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text("${formatNumber(axis.min)} – ${formatNumber(axis.max)} · پیش‌فرض ${formatNumber(axis.default)}", style = MaterialTheme.typography.bodySmall)
                                            TextButton(onClick = { library.axisValues[axis.tag] = axis.default }) { Text("بازنشانی") }
                                        }
                                    }
                                }
                                2 -> {
                                    if (entry.info.features.isEmpty()) Text("قابلیت قابل‌تنظیمی در GSUB یافت نشد یا قابل‌خواندن نیست.")
                                    else Text("وجود ویژگی در فونت، کاربرد آن را برای همهٔ متن‌ها تضمین نمی‌کند.", style = MaterialTheme.typography.bodySmall)
                                    entry.info.features.forEach { feature ->
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(feature, modifier = Modifier.weight(1f)); Switch(checked = library.featureValues[feature] == true, onCheckedChange = { library.featureValues[feature] = it })
                                        }
                                    }
                                }
                                3 -> {
                                    Text("فایل: ${entry.name}")
                                    Text("مسیر: ${entry.location}")
                                    Text("فرمت: ${entry.info.format}")
                                    val fields = listOf(16 to "خانوادهٔ ترجیحی", 17 to "سبک ترجیحی", 1 to "خانواده", 2 to "سبک", 4 to "نام کامل", 6 to "نام PostScript", 3 to "شناسه", 9 to "طراح", 8 to "سازنده", 5 to "نسخه", 0 to "حق نشر", 13 to "مجوز")
                                    fields.forEach { (id, label) -> Text("$label: ${entry.info.names[id]?.joinToString(" / ") ?: "موجود نیست"}") }
                                    entry.info.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                                    Text(library.status, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatNumber(value: Float): String = String.format(Locale.ROOT, "%.2f", value)

@Composable
private fun SimpleSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(formatNumber(value), style = MaterialTheme.typography.bodyMedium)
        }
        if (range.start < range.endInclusive) androidx.compose.material.Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, enabled = enabled, modifier = Modifier.fillMaxWidth())
    }
}
