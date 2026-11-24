@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.fontmanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val library: FontLibrary by viewModels()
    private val openFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { library.importFiles(it) }
    private val openFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(library::addSource) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent { FontApp(library, { openFiles.launch(arrayOf("*/*")) }, { openFolder.launch(null) }) }
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
private fun FontApp(library: FontLibrary, openFiles: () -> Unit, openFolder: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        if (library.selected == null) LibraryScreen(library, openFiles, openFolder) else PreviewScreen(library)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(library: FontLibrary, openFiles: () -> Unit, openFolder: () -> Unit) {
    var showSources by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    val filtered = remember(library.query, library.filters, library.fonts.size) { library.visibleFonts() }
    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(title = { Text("کتابخانه فونت", fontWeight = FontWeight.Bold) })
                TextField(
                    value = library.query, onValueChange = { library.query = it },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    singleLine = true, shape = CircleShape,
                    placeholder = { Text("جست‌وجوی فونت یا منبع") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = { if (library.query.isNotEmpty()) IconButton(onClick = { library.query = "" }) { Icon(Icons.Default.Close, "پاک کردن جست‌وجو") } },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
            }
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = openFiles, icon = { Icon(Icons.Default.Add, null) }, text = { Text("باز کردن فونت") }) }
    ) { padding ->
        LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    // Filters live next to the count so the active state is always visible.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${filtered.size} مورد", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        if (library.filters.isActive) TextButton(onClick = { library.filters = FontFilters() }) { Text("پاک کردن") }
                        FilterChip(
                            selected = showFilters || library.filters.isActive,
                            onClick = { showFilters = !showFilters },
                            label = { Text(if (library.filters.isActive) "فیلترها (${library.filters.activeCount})" else "فیلترهای پیشرفته") },
                            leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(FilterChipDefaults.IconSize)) }
                        )
                    }
                    if (showFilters) AdvancedFilters(library)
                    if (library.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (library.scanning) "در انتظار نتایج…" else if (library.fonts.isEmpty()) "فونتی یافت نشد. فایل یا پوشه‌ای اضافه کنید." else "هیچ فونتی با این جست‌وجو و فیلترها هم‌خوانی ندارد.",
                    modifier = Modifier.padding(vertical = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(filtered) { entry ->
                FontCard(entry) { library.open(entry) }
            }
        }
    }
}

@Composable
private fun FontCard(entry: FontEntry, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().height(176.dp)) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            // The specimen is written in the font itself; no placeholder letters.
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                FontSpecimen(entry, modifier = Modifier.fillMaxWidth())
            }
            Text(entry.info.names[16]?.firstOrNull() ?: entry.info.names[1]?.firstOrNull() ?: entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.info.format, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (entry.info.isVariable) Text("متغیر", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (entry.info.colored) Text("رنگی", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(entry.location, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Renders a short specimen in the font being previewed, loaded from its own file. */
@Composable
private fun FontSpecimen(entry: FontEntry, modifier: Modifier = Modifier) {
    val file = remember(entry) { entry.privateFile }
    val family = remember(entry.cacheKey) {
        file?.takeIf { entry.info.format == "TTF" || entry.info.format == "OTF" }?.let {
            runCatching { androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.createFromFile(it)) }.getOrNull()
        }
    }
    val style = MaterialTheme.typography.titleLarge.copy(
        fontFamily = family,
        fontWeight = FontWeight.Normal,
        color = MaterialTheme.colorScheme.primary
    )
    if (family == null) {
        // Folder fonts are not copied to internal storage, so their own glyphs are not available
        // here; the specimen falls back to the family name instead of invented sample letters.
        Text(
            entry.info.names[16]?.firstOrNull() ?: entry.info.names[1]?.firstOrNull() ?: entry.name,
            modifier = modifier, style = MaterialTheme.typography.titleSmall, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    } else {
        Text(
            "Aa bb ۱۲۳", modifier = modifier, style = style, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun AdvancedFilters(library: FontLibrary) {
    val filters = library.filters
    fun update(block: (FontFilters) -> FontFilters) { library.filters = block(library.filters) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterGroup("فرمت") {
                FORMAT_CHOICES.forEach { format ->
                    FilterChip(
                        selected = format in filters.formats,
                        onClick = { update { f -> f.copy(formats = if (format in f.formats) f.formats - format else f.formats + format) } },
                        label = { Text(format) }
                    )
                }
            }
            FilterGroup("نوع") {
                FilterChip(selected = filters.variableOnly, onClick = { update { it.copy(variableOnly = !it.variableOnly, staticOnly = false) } }, label = { Text("متغیر") })
                FilterChip(selected = filters.staticOnly, onClick = { update { it.copy(staticOnly = !it.staticOnly, variableOnly = false) } }, label = { Text("ثابت") })
                FilterChip(selected = filters.colored, onClick = { update { it.copy(colored = !it.colored) } }, label = { Text("رنگی") })
            }
            FilterGroup("وزن") {
                WEIGHT_BUCKETS.forEach { bucket ->
                    FilterChip(
                        selected = bucket.value in filters.weights,
                        onClick = { update { f -> f.copy(weights = if (bucket.value in f.weights) f.weights - bucket.value else f.weights + bucket.value) } },
                        label = { Text("${bucket.label} ${bucket.value}") }
                    )
                }
            }
            FilterGroup("کپی‌رایت") { TriStateChips(filters.copyright) { update { f -> f.copy(copyright = it) } } }
            FilterGroup("خط عربی") { TriStateChips(filters.arabicScript) { update { f -> f.copy(arabicScript = it) } } }
            FilterGroup("حروف فارسی (پ چ ژ گ ک ی)") { TriStateChips(filters.persianLetters) { update { f -> f.copy(persianLetters = it) } } }
            FilterGroup("لایسنس") {
                LICENSE_ORDER.forEach { license ->
                    FilterChip(
                        selected = license in filters.licenses,
                        onClick = { update { f -> f.copy(licenses = if (license in f.licenses) f.licenses - license else f.licenses + license) } },
                        label = { Text(licenseLabel(license)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterGroup(title: String, content: @Composable FlowRowScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun TriStateChips(value: TriState, onChange: (TriState) -> Unit) {
    FilterChip(selected = value == TriState.Any, onClick = { onChange(TriState.Any) }, label = { Text("همه") })
    FilterChip(selected = value == TriState.Yes, onClick = { onChange(TriState.Yes) }, label = { Text("دارد") })
    FilterChip(selected = value == TriState.No, onClick = { onChange(TriState.No) }, label = { Text("ندارد") })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewScreen(library: FontLibrary) {
    val entry = library.selected ?: return
    BackHandler { library.back() }
    var tab by remember(entry) { mutableIntStateOf(0) }
    val previewColor = MaterialTheme.colorScheme.onSurface.toArgb()
    Scaffold(topBar = {
        TopAppBar(title = { Text(entry.info.names[16]?.firstOrNull() ?: entry.info.names[1]?.firstOrNull() ?: entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = library::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") } })
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
                    else if (library.face == null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                if (entry.info.format in listOf("WOFF", "WOFF2")) "پیش‌نمایش این فرمت هنوز پشتیبانی نمی‌شود." else library.status,
                                modifier = Modifier.padding(20.dp)
                            )
                            TextButton(onClick = library::back) { Text("بازگشت به کتابخانه") }
                        }
                    }
                    else PreviewText(library, previewColor, Modifier.fillMaxSize().padding(horizontal = 16.dp))
                }
                Surface(modifier = Modifier.fillMaxWidth().height(actualHeight.dp), color = MaterialTheme.colorScheme.surfaceContainer, shape = androidx.compose.ui.graphics.RectangleShape, tonalElevation = 2.dp) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(28.dp).pointerInput(minHeight, maxPanelHeight) {
                            detectVerticalDragGestures { change, amount -> change.consume(); panelHeight = (panelHeight.coerceIn(minHeight, maxPanelHeight) - with(density) { amount.toDp().value }).coerceIn(minHeight, maxPanelHeight) }
                        }, contentAlignment = Alignment.Center) {
                            Box(Modifier.width(42.dp).height(4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                        }
                        TabRow(selectedTabIndex = tab) {
                            listOf("چیدمان", "محورها", "ویژگی‌ها", "درباره").forEachIndexed { index, name ->
                                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(name, maxLines = 1) })
                            }
                        }
                        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (tab) {
                                0 -> {
                                    SimpleSlider("اندازه متن", library.size, 12f..132f) { library.size = it }
                                    SimpleSlider("ارتفاع خط", library.lineSpacing, 0.7f..2.5f) { library.lineSpacing = it }
                                    Text("تراز", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextAlignment.entries.forEach { option ->
                                            FilterChip(
                                                selected = library.alignment == option,
                                                onClick = { library.alignment = option },
                                                label = { Text(option.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                                1 -> {
                                    if (entry.info.axes.isEmpty()) Text("محور متغیری یافت نشد یا قابل‌خواندن نیست.")
                                    entry.info.axes.forEach { axis ->
                                        val value = library.axisValues[axis.tag] ?: axis.default
                                        AxisRow(axis, value, enabled = axis.min < axis.max && !library.opening) { library.setAxis(axis.tag, it) }
                                    }
                                }
                                2 -> {
                                    if (entry.info.features.isEmpty()) Text("قابلیت قابل‌تنظیمی در GSUB یافت نشد یا قابل‌خواندن نیست.")
                                    else {
                                        Text("وجود ویژگی در فونت، کاربرد آن را برای همهٔ متن‌ها تضمین نمی‌کند.", style = MaterialTheme.typography.bodySmall)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            entry.info.features.forEach { feature ->
                                                val state = library.featureValues[feature] == true
                                                FilterChip(
                                                    selected = state,
                                                    onClick = { library.featureValues[feature] = !state },
                                                    label = { Text(feature) },
                                                    leadingIcon = if (state) {
                                                        { Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                                                    } else null
                                                )
                                            }
                                        }
                                    }
                                }
                                3 -> {
                                    AboutTable(entry)
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

/** Slider row for one variable axis. Bold title, icon-only reset, no default value in the label. */
@Composable
private fun AxisRow(axis: VariableAxis, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                axis.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { onChange(axis.default) }, enabled = enabled && value != axis.default) {
                Icon(Icons.Default.RestartAlt, contentDescription = "بازنشانی ${axis.name}", modifier = Modifier.size(20.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${formatNumber(axis.min)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = value.coerceIn(axis.min, axis.max),
                onValueChange = onChange,
                valueRange = axis.min..axis.max,
                enabled = enabled && axis.min < axis.max,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Text("${formatNumber(axis.max)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PreviewText(library: FontLibrary, textColor: Int, modifier: Modifier = Modifier) {
    val variation = library.variationSettings()
    val features = library.featureSettings()
    AndroidView(
        modifier = modifier,
        factory = { context -> PreviewEditText(context).also { view -> view.onTextEdited = { library.text = it } } },
        update = { view ->
            view.update(
                PreviewEditText.State(
                    text = library.text,
                    typeface = library.face,
                    // Settings are in sp; the view works in px.
                    textSizePx = with(view.resources.displayMetrics) { library.size * scaledDensity },
                    lineMultiplier = library.lineSpacing,
                    textColor = textColor,
                    alignment = library.alignment,
                    fontFeatureSettings = features.ifEmpty { null },
                    fontVariationSettings = variation.ifEmpty { null }
                )
            )
        }
    )
}

@Composable
private fun AboutTable(entry: FontEntry) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val fields = listOf(16 to "خانوادهٔ ترجیحی", 17 to "سبک ترجیحی", 1 to "خانواده", 2 to "سبک", 4 to "نام کامل", 6 to "نام PostScript", 3 to "شناسه", 9 to "طراح", 8 to "سازنده", 5 to "نسخه", 0 to "حق نشر", 13 to "مجوز")
    val rows = listOf("فایل" to entry.name, "مسیر" to entry.location, "فرمت" to entry.info.format, "لایسنس" to licenseLabel(entry.info.license)) +
        fields.map { (id, label) -> label to entry.info.names[id]?.joinToString(" / ") }
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(.33f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value ?: "موجود نیست", modifier = Modifier.weight(.67f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = {
                    if (value != null) {
                        clipboard.setText(AnnotatedString(value))
                        android.widget.Toast.makeText(context, "کپی شد", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, enabled = value != null) { Icon(Icons.Default.ContentCopy, contentDescription = "کپی $label", modifier = Modifier.size(18.dp)) }
            }
            HorizontalDivider()
        }
    }
}

/** Whole numbers stay whole (font weights read "400", not "400.00"); finer axes keep two decimals. */
private fun formatNumber(value: Float): String =
    String.format(Locale.ROOT, if (value % 1f == 0f) "%.0f" else "%.2f", value)

/** Slider on the Material 3 palette, so its track and thumb follow the device's dynamic colours. */
@Composable
private fun SimpleSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(formatNumber(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (range.start < range.endInclusive) {
            Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, enabled = enabled, modifier = Modifier.fillMaxWidth())
        }
    }
}
