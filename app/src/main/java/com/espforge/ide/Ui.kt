package com.espforge.ide

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.espforge.ide.fs.FileNode
import com.espforge.ide.fs.Project
import com.espforge.ide.fs.Templates
import com.espforge.ide.fs.Workspace
import java.io.File

private val Bg = Color(0xFF12141A)
private val Panel = Color(0xFF171A21)
private val PanelAlt = Color(0xFF1C2029)
private val Stroke = Color(0xFF262B36)
private val Accent = Color(0xFF4EA1FF)
private val Green = Color(0xFF7CE38B)
private val Muted = Color(0xFF8B95A5)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IdeScreen(c: IdeController) {
    val imeVisible = WindowInsets.isImeVisible
    val density = LocalDensity.current
    // 终端/串口拿焦点时把底部面板抬到键盘上方；编辑器和搜索框不动
    val dockLift = if (imeVisible && c.dockImeFocused) WindowInsets.ime.getBottom(density) else 0
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    // 打开搜索时自动聚焦到顶栏输入框（键盘自动弹出）
    LaunchedEffect(c.searchOpen) {
        if (c.searchOpen) {
            try {
                kotlinx.coroutines.delay(80)
                searchFocus.requestFocus()
            } catch (_: Throwable) {
            }
        }
    }

    LaunchedEffect(c.toast) {
        val t = c.toast
        if (t != null) {
            c.toast = null
            snackbar.showSnackbar(t)
        }
    }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Panel,
                    titleContentColor = Color(0xFFD7DCE5),
                ),
                title = {
                    if (c.searchOpen) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            // Box 只包住搜索框本身：浮窗用 Popup 锚在它下方，
                            // 左边缘对齐、零动画，不会有一瞬间盖住输入框
                            Box(Modifier.width(300.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(17.dp))
                                        .background(Color(0xFF262B36))
                                        .padding(start = 11.dp, end = 3.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = null,
                                        tint = Green,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(Modifier.width(7.dp))
                                    BasicTextField(
                                        value = c.searchQuery,
                                        onValueChange = {
                                            c.searchQuery = it
                                            c.runSearch()
                                        },
                                        singleLine = true,
                                        textStyle = LocalTextStyle.current.copy(
                                            fontSize = 13.sp,
                                            color = Color(0xFFE6EAF2),
                                        ),
                                        cursorBrush = SolidColor(Green),
                                        modifier = Modifier
                                            .weight(1f)
                                            .focusRequester(searchFocus),
                                        decorationBox = { inner ->
                                            if (c.searchQuery.isEmpty()) {
                                                Text(
                                                    "在工程中搜索…",
                                                    fontSize = 13.sp,
                                                    color = Color(0xFF7A8494),
                                                )
                                            }
                                            inner()
                                        },
                                    )
                                    Text(
                                        text = if (c.searchBusy) "…" else c.searchResults.size.toString(),
                                        fontSize = 10.sp,
                                        color = Muted,
                                        modifier = Modifier.padding(horizontal = 5.dp),
                                    )
                                    IconButton(
                                        onClick = { c.searchOpen = false },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "关闭搜索",
                                            tint = Muted,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }

                                if (c.searchResults.isNotEmpty()) {
                                    val gapPx = with(LocalDensity.current) { 40.dp.roundToPx() }
                                    Popup(
                                        alignment = Alignment.TopStart,
                                        offset = IntOffset(0, gapPx),
                                        properties = PopupProperties(focusable = false),
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = Color(0xFF1B2029),
                                            border = BorderStroke(1.dp, Color(0xFF2E3542)),
                                            shadowElevation = 14.dp,
                                            modifier = Modifier.width(330.dp),
                                        ) {
                                            Column(
                                                Modifier
                                                    .heightIn(max = 300.dp)
                                                    .verticalScroll(rememberScrollState())
                                                    .padding(vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = c.searchResults.size.toString() + " 个结果" +
                                                        (if (c.searchSummary.isNotBlank()) " · " + c.searchSummary else ""),
                                                    fontSize = 10.sp,
                                                    color = Muted,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                                                )
                                                val root = c.project?.dir ?: File("/")
                                                c.searchResults.take(200).forEach { h ->
                                                    val rel = Workspace.relativePath(root, File(h.path))
                                                    Row(
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                c.openHit(h)
                                                                c.searchOpen = false
                                                            }
                                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Text(
                                                            text = rel + ":" + h.line,
                                                            fontSize = 10.5.sp,
                                                            color = Accent,
                                                            maxLines = 1,
                                                        )
                                                        Spacer(Modifier.width(10.dp))
                                                        Text(
                                                            text = h.text.trim(),
                                                            fontSize = 10.5.sp,
                                                            color = Color(0xFFC7CEDA),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = c.active?.name ?: (c.project?.name ?: "EspForge"),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (c.dirty) {
                                    Box(
                                        Modifier
                                            .padding(start = 6.dp)
                                            .size(7.dp)
                                            .background(Accent, RoundedCornerShape(50))
                                    )
                                }
                                if (c.active != null) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(c.activeLang?.name ?: "", fontSize = 11.sp, color = Muted)
                                }
                            }
                            if (c.active != null) {
                                Text(
                                    text = c.project?.name ?: "",
                                    fontSize = 10.sp,
                                    color = Color(0xFF5C6773),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { c.showSidebar = !c.showSidebar }) {
                        Icon(Icons.Filled.Folder, contentDescription = "资源管理器", tint = Muted)
                    }
                },
                actions = {
                    val idle = Muted
                    IconButton(onClick = { c.toggleSearch() }) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "搜索",
                            tint = if (c.searchOpen) Green else idle,
                        )
                    }
                    if (c.building) {
                        IconButton(onClick = { c.stopBuild() }) {
                            Icon(Icons.Filled.Stop, contentDescription = "停止构建", tint = Green)
                        }
                    } else {
                        IconButton(onClick = { c.build() }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "构建", tint = idle)
                        }
                    }
                    // 保存只在打开了文件时出现，避免点了没反应
                    if (c.active != null) {
                        IconButton(onClick = { c.js("CP.requestSave()") }) {
                            Icon(Icons.Filled.Save, contentDescription = "保存", tint = idle)
                        }
                    }
                    IconButton(onClick = {
                        c.showTerminal = !c.showTerminal
                        c.ensureTerminal()
                    }) {
                        Icon(
                            Icons.Filled.Terminal,
                            contentDescription = "终端",
                            tint = if (c.showTerminal) Green else idle,
                        )
                    }
                    IconButton(onClick = { c.openDialog("newFile", c.project?.dir) }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建文件", tint = idle)
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "更多",
                                tint = if (menuOpen) Green else idle,
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("新建文件") },
                                onClick = { menuOpen = false; c.openDialog("newFile", c.project?.dir) },
                            )
                            DropdownMenuItem(
                                text = { Text("新建文件夹") },
                                onClick = { menuOpen = false; c.openDialog("newFolder", c.project?.dir) },
                            )
                            DropdownMenuItem(
                                text = { Text("重命名当前文件") },
                                onClick = { menuOpen = false; c.active?.let { c.openDialog("renameNode", it) } },
                            )
                            DropdownMenuItem(
                                text = { Text("删除当前文件") },
                                onClick = { menuOpen = false; c.active?.let { c.openDialog("deleteNode", it) } },
                            )
                            DropdownMenuItem(
                                text = { Text("切换断点（当前行）") },
                                onClick = { menuOpen = false; c.js("CP.toggleBreakpointAtCursor()") },
                            )
                            HorizontalDivider(color = Stroke)
                            DropdownMenuItem(
                                text = { Text("选择目标芯片…（" + c.chipLabel() + "）") },
                                onClick = { menuOpen = false; c.openDialog("chip") },
                            )
                            DropdownMenuItem(
                                text = { Text("导出工程（zip）") },
                                onClick = { menuOpen = false; c.startExport() },
                            )
                            DropdownMenuItem(
                                text = { Text("导入工程（zip）") },
                                onClick = { menuOpen = false; c.startImport() },
                            )
                            DropdownMenuItem(
                                text = { Text("新建工程") },
                                onClick = { menuOpen = false; c.openDialog("newProject") },
                            )
                            DropdownMenuItem(
                                text = { Text("ESP-IDF 资源包…") },
                                onClick = { menuOpen = false; c.openDialog("pack") },
                            )
                            DropdownMenuItem(
                                text = { Text("设置…") },
                                onClick = { menuOpen = false; c.openDialog("settings") },
                            )
                            DropdownMenuItem(
                                text = { Text("重载扩展") },
                                onClick = { menuOpen = false; c.reloadExtensions() },
                            )
                            DropdownMenuItem(
                                text = { Text("关于 EspForge") },
                                onClick = { menuOpen = false; c.openDialog("about") },
                            )
                        }
                    }
                }
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            TabStrip(c)
            HorizontalDivider(color = Stroke)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (c.showSidebar) {
                    ExplorerPanel(c, Modifier.width(250.dp).fillMaxHeight())
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Stroke))
                }
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { EditorPane(c) }
                    }
                }
            }
            // 底部面板一直显示，不做任何"让位"：编辑/搜索时让虚拟键盘自己盖住它就行。
            // （之前把高度收到 0 / 从组合里摘掉，都会让终端 WebView 反复 attach、
            //  点终端标签时直接崩，所以彻底不做这件事。）
            //
            // 例外：终端/串口里的 WebView 拿到焦点时，键盘会把面板整个挡住，没法边看边敲。
            // 这时把「面板 + 状态栏」整体向上平移键盘高度（只是平移，不改布局高度，
            // 所以编辑区依旧不被挤压）。
            Column(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, -dockLift) }
            ) {
                if (c.showTerminal) {
                    HorizontalDivider(color = Stroke)
                    BottomDock(c, c.terminalHeight)
                }
                StatusBar(c)
            }
        }
        }
    }

    IdeDialogs(c)
}

@Composable
private fun SearchHitsBar(c: IdeController) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(Color(0xFF151922))
            .verticalScroll(scroll)
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = c.searchResults.size.toString() + " 个结果" +
                (if (c.searchSummary.isNotBlank()) " · " + c.searchSummary else ""),
            fontSize = 10.sp,
            color = Muted,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
        val root = c.project?.dir ?: File("/")
        c.searchResults.take(300).forEach { h ->
            val rel = Workspace.relativePath(root, File(h.path))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { c.openHit(h) }
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = rel + ":" + h.line,
                    fontSize = 10.5.sp,
                    color = Accent,
                    maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = h.text.trim(),
                    fontSize = 10.5.sp,
                    color = Color(0xFFC7CEDA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider(color = Stroke)
}

@Composable
private fun TabStrip(c: IdeController) {
    if (c.tabs.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(Panel)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        c.tabs.forEach { f ->
            val selected = f == c.active
            Row(
                Modifier
                    .padding(horizontal = 2.dp, vertical = 3.dp)
                    .background(
                        if (selected) PanelAlt else Color.Transparent,
                        RoundedCornerShape(6.dp),
                    )
                    .clickable { c.openFile(f) }
                    .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FileTypeIcon(f.name, false, false, 12.dp)
                Spacer(Modifier.width(5.dp))
                Text(
                    text = f.name,
                    fontSize = 12.sp,
                    color = if (selected) Color(0xFFE6EAF2) else Muted,
                    maxLines = 1,
                )
                IconButton(
                    onClick = { c.closeTab(f) },
                    modifier = Modifier.size(20.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = Muted,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

private class DragHost {
    var node: FileNode? by mutableStateOf(null)
    var pos: Offset by mutableStateOf(Offset.Zero)
    var travelled: Float by mutableStateOf(0f)
    var panelOrigin: Offset by mutableStateOf(Offset.Zero)
    val rects = HashMap<String, Rect>()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExplorerPanel(c: IdeController, modifier: Modifier) {
    var projectMenu by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<File?>(null) }
    val host = remember { DragHost() }

    val hoverPath = if (host.node != null) {
        host.rects.entries.firstOrNull { it.value.contains(host.pos) }?.key
    } else null

    Column(modifier.background(Panel)) {
        // 工程切换条
        Row(
            Modifier
                .fillMaxWidth()
                .background(PanelAlt)
                .clickable { projectMenu = true }
                .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.FolderOpen, null, tint = Color(0xFFE0B25C), modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                c.project?.name ?: "未打开工程",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE6EAF2),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ArrowDropDown, null, tint = Muted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
            c.projects.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (p.id == c.project?.id) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                                null,
                                tint = if (p.id == c.project?.id) Accent else Muted,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(p.name, fontSize = 13.sp)
                        }
                    },
                    onClick = {
                        projectMenu = false
                        if (p.id != c.project?.id) c.switchProject(p)
                    },
                )
            }
            HorizontalDivider(color = Stroke)
            DropdownMenuItem(
                text = { Text("＋ 新建工程…") },
                onClick = { projectMenu = false; c.openDialog("newProject") },
            )
            DropdownMenuItem(
                text = { Text("重命名当前工程…") },
                onClick = { projectMenu = false; c.openDialog("renameProject") },
            )
            DropdownMenuItem(
                text = { Text("删除当前工程…") },
                onClick = { projectMenu = false; c.openDialog("deleteProject") },
            )
        }

        // 工具条
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "资源管理器",
                fontSize = 10.sp,
                color = Color(0xFF5C6773),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { c.refreshTree() }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Refresh, null, tint = Muted, modifier = Modifier.size(14.dp))
            }
            IconButton(onClick = { c.openDialog("newFile", c.project?.dir) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Add, null, tint = Muted, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { c.openDialog("newFolder", c.project?.dir) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.CreateNewFolder, null, tint = Muted, modifier = Modifier.size(15.dp))
            }
        }
        HorizontalDivider(color = Stroke)

        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { host.panelOrigin = it.positionInRoot() }
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                userScrollEnabled = host.node == null,
            ) {
                items(c.files, key = { it.file.absolutePath }) { node ->
                    TreeRow(
                        c = c,
                        host = host,
                        node = node,
                        menuOpen = menuFor == node.file,
                        onMenu = { menuFor = it },
                        isDropTarget = hoverPath == node.file.absolutePath,
                        onDropFinished = { n, moved ->
                            if (moved < 26f) {
                                menuFor = n.file
                            } else {
                                val key = host.rects.entries.firstOrNull { it.value.contains(host.pos) }?.key
                                val t = key?.let { p -> c.files.firstOrNull { it.file.absolutePath == p } }
                                if (t != null) {
                                    val dest = if (t.isDir) t.file else t.file.parentFile
                                    if (dest != null && dest.absolutePath != n.file.parentFile?.absolutePath) {
                                        c.moveNode(n.file, dest)
                                    }
                                }
                            }
                        },
                    )
                }
                if (c.files.isEmpty()) {
                    item {
                        Text(
                            "空工程 · 点右上角 + 新建文件",
                            fontSize = 11.sp,
                            color = Color(0xFF4A5261),
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
                item {
                    Text(
                        "长按拖动文件到文件夹，或松手打开菜单",
                        fontSize = 10.sp,
                        color = Color(0xFF3E4552),
                        modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 14.dp),
                    )
                }
            }

            val drag = host.node
            if (drag != null) {
                val p = host.pos - host.panelOrigin
                Row(
                    Modifier
                        .offset { IntOffset(p.x.toInt() + 14, p.y.toInt() - 34) }
                        .background(Color(0xFF2B3A55), RoundedCornerShape(7.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileTypeIcon(drag.name, drag.isDir, false, 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(drag.name, fontSize = 11.sp, color = Color(0xFFE6EAF2))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRow(
    c: IdeController,
    host: DragHost,
    node: FileNode,
    menuOpen: Boolean,
    onMenu: (File?) -> Unit,
    isDropTarget: Boolean,
    onDropFinished: (FileNode, Float) -> Unit,
) {
    val selected = node.file == c.active
    val isOpen = c.expanded.contains(node.file.absolutePath)

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .onGloballyPositioned { host.rects[node.file.absolutePath] = it.boundsInRoot() }
                .background(
                    when {
                        isDropTarget -> Color(0xFF24405F)
                        selected -> Color(0xFF1F2937)
                        else -> Color.Transparent
                    }
                )
                .clickable {
                    if (node.isDir) c.toggleFolder(node) else c.openFile(node.file)
                }
                .pointerInput(node.file.absolutePath) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { local ->
                            val b = host.rects[node.file.absolutePath]
                            host.node = node
                            host.pos = (b?.topLeft ?: Offset.Zero) + local
                            host.travelled = 0f
                        },
                        onDrag = { change, delta ->
                            change.consume()
                            host.pos += delta
                            host.travelled += delta.getDistance()
                        },
                        onDragEnd = {
                            val n = host.node
                            val moved = host.travelled
                            host.node = null
                            host.travelled = 0f
                            if (n != null) onDropFinished(n, moved)
                        },
                        onDragCancel = {
                            host.node = null
                            host.travelled = 0f
                        },
                    )
                }
                .padding(start = (6 + node.depth * 14).dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (node.isDir) {
                Icon(
                    imageVector = if (isOpen) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Muted,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(3.dp))
                FileTypeIcon(node.name, true, isOpen, 15.dp)
            } else {
                Spacer(Modifier.width(17.dp))
                FileTypeIcon(node.name, false, false, 15.dp)
            }
            Spacer(Modifier.width(7.dp))
            Text(
                node.name,
                fontSize = 12.sp,
                color = if (node.isDir) Color(0xFFD7DCE5) else Color(0xFFC2C9D6),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onMenu(node.file) }, modifier = Modifier.size(22.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "文件操作",
                    tint = Color(0xFF5C6773),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(null) }) {
            val parent = if (node.isDir) node.file else (node.file.parentFile ?: c.project!!.dir)
            DropdownMenuItem(
                text = { Text("新建文件") },
                onClick = { onMenu(null); c.openDialog("newFile", parent) },
            )
            DropdownMenuItem(
                text = { Text("新建文件夹") },
                onClick = { onMenu(null); c.openDialog("newFolder", parent) },
            )
            if (node.isDir) {
                DropdownMenuItem(
                    text = { Text("展开 / 折叠") },
                    onClick = { onMenu(null); c.toggleFolder(node) },
                )
            }
            DropdownMenuItem(
                text = { Text("移动到文件夹…") },
                onClick = { onMenu(null); c.openDialog("moveNode", node.file) },
            )
            if (node.file.parentFile?.absolutePath != c.project?.dir?.absolutePath) {
                DropdownMenuItem(
                    text = { Text("移到上一层") },
                    onClick = {
                        onMenu(null)
                        node.file.parentFile?.parentFile?.let { up -> c.moveNode(node.file, up) }
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("重命名") },
                onClick = { onMenu(null); c.openDialog("renameNode", node.file) },
            )
            DropdownMenuItem(
                text = { Text("删除", color = Color(0xFFFF8A80)) },
                onClick = { onMenu(null); c.openDialog("deleteNode", node.file) },
            )
        }
    }
}

@Composable
private fun EditorPane(c: IdeController) {
    Box(Modifier.fillMaxSize().background(Bg)) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (c.active == null) 0f else 1f),
            factory = { ctx -> createEditorWebView(ctx, c) },
        )
        if (c.active == null) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    c.project?.name ?: "CodePad",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF3C4454),
                )
                Spacer(Modifier.height(8.dp))
                Text("从左侧选择文件，或长按文件夹新建", fontSize = 12.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun BottomDock(c: IdeController, panelHeight: Int) {
    val density = LocalDensity.current
    Column(
        Modifier
            .fillMaxWidth()
            .height(panelHeight.dp)
            .background(Color(0xFF0E1015))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(Panel)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        val deltaDp = with(density) { dragAmount.toDp().value }
                        c.terminalHeight = (c.terminalHeight - deltaDp).toInt().coerceIn(140, 640)
                    }
                }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockTab("终端", c.bottomTab == TERMINAL_TAB) { c.bottomTab = TERMINAL_TAB }
            Spacer(Modifier.width(4.dp))
            DockTab(
                if (c.building) "构建 ●" else "构建",
                c.bottomTab == BUILD_TAB,
            ) { c.bottomTab = BUILD_TAB }
            Spacer(Modifier.width(4.dp))
            DockTab(
                if (c.flashing) "烧录 ●" else "烧录",
                c.bottomTab == FLASH_TAB,
            ) { c.bottomTab = FLASH_TAB }
            Spacer(Modifier.width(4.dp))
            DockTab(
                if (c.serialOpen) "串口 ●" else "串口",
                c.bottomTab == SERIAL_TAB,
            ) { c.bottomTab = SERIAL_TAB }
            Spacer(Modifier.width(4.dp))
            DockTab(
                if (c.debugActive && c.debugPaused) "调试 ●" else "调试",
                c.bottomTab == DEBUG_TAB,
            ) { c.bottomTab = DEBUG_TAB }
            Spacer(Modifier.width(4.dp))
            DockTab("Git", c.bottomTab == GIT_TAB) {
                c.bottomTab = GIT_TAB
                c.gitRefresh()
            }

            Spacer(Modifier.weight(1f))

            // 每个 Tab 的右侧动作统一：28dp 图标、16dp、只有图标没有文字，
            // 主操作在左、危险操作(关闭/断开/停止)在右且用红色。
            if (c.bottomTab == TERMINAL_TAB) {
                DockAction(Icons.Filled.Delete, "清屏") { c.clearTerminal() }
                DockAction(Icons.Filled.Refresh, "重启会话") { c.restartTerminal() }
                DockAction(Icons.Filled.Close, "关闭面板") { c.showTerminal = false }
            } else if (c.bottomTab == BUILD_TAB) {
                DockAction(Icons.Filled.PlayArrow, "构建", Green) { c.build() }
                DockAction(Icons.Filled.Refresh, "清理") { c.cleanBuild() }
                DockAction(Icons.Filled.Build, "自检") { c.runSelfCheck() }
                DockAction(Icons.Filled.Stop, "停止", Color(0xFFFF8A80), enabled = c.building) { c.stopBuild() }
            } else if (c.bottomTab == FLASH_TAB) {
                // 烧录自己会在没连串口时去连；这里不再放「构建」（构建页有）
                DockAction(Icons.Filled.FlashOn, "烧录", Color(0xFFFFC857), enabled = !c.flashing) { c.flash() }
                DockAction(Icons.Filled.Delete, "清空烧录日志") { c.clearFlashLog() }
            } else if (c.bottomTab == SERIAL_TAB) {
                if (c.serialOpen) {
                    DockAction(Icons.Filled.Close, "断开串口", Color(0xFFFF8A80)) { c.disconnectSerial() }
                } else {
                    DockAction(Icons.Filled.Usb, "连接串口", Green) { c.connectSerial() }
                }
                DockAction(Icons.Filled.Refresh, "复位芯片", enabled = c.serialOpen) { c.serialReset() }
                DockAction(Icons.Filled.Download, "进入下载模式", enabled = c.serialOpen) { c.serialToBootloader() }
                DockAction(Icons.Filled.Delete, "清屏", enabled = c.serialOpen) { c.serialJs("CP.term.clear()") }
            }
            // 调试 Tab 的「开始调试」在面板里给说明，不放只会弹提示的图标按钮
        }
        HorizontalDivider(color = Stroke)
        Box(Modifier.fillMaxSize()) {
            // 终端 WebView 只在终端页挂载：它一直挂着会盖住构建/烧录/调试面板
            // （AndroidView 是原生层，Compose 后画的兄弟节点压不住它）
            if (c.bottomTab == TERMINAL_TAB) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    // 复用已有 WebView 实例：切换 Tab 只做 detach/attach，不重建，PTY 会话不丢
                    factory = { ctx ->
                        val existing = c.terminalView
                        if (existing != null) {
                            (existing.parent as? android.view.ViewGroup)?.removeView(existing)
                            existing
                        } else {
                            createTerminalWebView(ctx, c)
                        }
                    },
                )
            }
            if (c.bottomTab == BUILD_TAB) {
                BuildContent(c)
            }
            if (c.bottomTab == FLASH_TAB) {
                FlashContent(c)
            }
            if (c.bottomTab == SERIAL_TAB) {
                SerialContent(c)
            }
            if (c.bottomTab == DEBUG_TAB) {
                DebugContent(c)
            }
            if (c.bottomTab == GIT_TAB) {
                GitContent(c)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockTab(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 11.sp,
        color = if (active) Color(0xFFE6EAF2) else Muted,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (active) PanelAlt else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun DockAction(
    icon: ImageVector,
    label: String,
    tint: Color = Muted,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        modifier = Modifier.size(28.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) tint else Color(0xFF3B4250),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun FlashContent(c: IdeController) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF141821))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = c.flashInfo,
                fontSize = 11.sp,
                color = if (c.flashing) Color(0xFFFFC857) else Muted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (c.serialOpen) c.serialLabel else "串口未连接",
                fontSize = 10.5.sp,
                color = if (c.serialOpen) Green else Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { c.flashProgress / 100f },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = Color(0xFFFFC857),
        )
        Spacer(Modifier.height(6.dp))
        val scroll = rememberScrollState()
        LaunchedEffect(c.flashLog) {
            try {
                scroll.scrollTo(scroll.maxValue)
            } catch (_: Throwable) {
            }
        }
        Text(
            text = c.flashLog.ifBlank { "连上板子后点「烧录」。固件来自 build/flasher_args.json（先构建一次）" },
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFD7DCE5),
            modifier = Modifier.fillMaxSize().verticalScroll(scroll),
        )
    }
}

@Composable
private fun SerialContent(c: IdeController) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .background(Panel)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (c.serialOpen) c.serialLabel else "未连接 USB 串口",
                fontSize = 10.5.sp,
                color = if (c.serialOpen) Green else Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            listOf(115200, 460800, 921600).forEach { baud ->
                Text(
                    text = "$baud",
                    fontSize = 10.sp,
                    color = if (c.serialBaud == baud) Accent else Muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { c.changeSerialBaud(baud) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val existing = c.serialView
                if (existing != null) {
                    (existing.parent as? android.view.ViewGroup)?.removeView(existing)
                    existing
                } else {
                    createSerialWebView(ctx, c)
                }
            },
        )
    }
}

@Composable
private fun BuildContent(c: IdeController) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF141821))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = c.chipLabel() + " · " + c.buildInfo,
                fontSize = 11.sp,
                color = when (c.buildOk) {
                    true -> Green
                    false -> Color(0xFFFF8A80)
                    else -> Muted
                },
            )
            Spacer(Modifier.weight(1f))
            // 构建/清理/自检都放在上面那排图标里了，这里不再重复放文字按钮
        }
        Spacer(Modifier.height(4.dp))
        if (!c.packReady) {
            Surface(
                color = Color(0xFF2A1E14),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, Color(0xFF6B4A22)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "还没有安装 ESP-IDF 资源包，所以构建会失败。",
                        fontSize = 12.sp,
                        color = Color(0xFFFFC857),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "资源包 = ESP-IDF 源码 + xtensa 交叉工具链 + cmake/ninja + python + glibc，" +
                            "一个 zip，导入后完全离线。",
                        fontSize = 10.5.sp,
                        color = Muted,
                    )
                    if (c.packStatus.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(c.packStatus, fontSize = 10.5.sp, color = Color(0xFFFFAB91))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        SmallAction("导入资源包") { c.openDialog("pack") }
                        if (c.externalPackFile() != null) {
                            Spacer(Modifier.width(6.dp))
                            SmallAction("导入 pack.zip") { c.importSideLoadedPack() }
                        }
                    }
                }
            }
        }
        val scroll = rememberScrollState()
        LaunchedEffect(c.buildLog) {
            try {
                scroll.scrollTo(scroll.maxValue)
            } catch (_: Throwable) {
            }
        }
        Text(
            text = c.buildLog.ifBlank { "点上面的 ▶ 开始构建，idf.py 的输出会显示在这里" },
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFD7DCE5),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        )
    }
}

@Composable
private fun GitContent(c: IdeController) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF141821))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (c.gitRepo) {
                Text(
                    "⎇ ${c.gitBranch.ifBlank { "?" }}",
                    fontSize = 11.sp,
                    color = Color(0xFF4EA1FF),
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                DockTab("改动 ${c.gitStatus.size}", c.gitTab == "changes") {
                    c.gitTab = "changes"
                    c.gitRefresh()
                }
                DockTab("历史", c.gitTab == "history") { c.gitShowHistory() }
            } else {
                Text("这个工程还不是 git 仓库", fontSize = 11.sp, color = Muted)
            }
            Spacer(Modifier.weight(1f))
            if (c.gitRepo && c.gitTab == "changes" && c.gitStatus.isNotEmpty()) {
                TextButton(onClick = { c.gitStageAll() }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("全部暂存", fontSize = 11.sp)
                }
            }
            TextButton(onClick = { c.gitRefresh() }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(if (c.gitBusy) "刷新中…" else "刷新", fontSize = 11.sp)
            }
            if (!c.gitRepo) {
                TextButton(onClick = { c.gitInit() }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("初始化", fontSize = 11.sp)
                }
            }
        }
        if (!c.gitRepo) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("点右上「初始化」把这个工程变成 git 仓库", fontSize = 11.sp, color = Muted)
            }
            return@Column
        }
        HorizontalDivider(color = Stroke)
        Row(Modifier.weight(1f)) {
            if (c.gitTab == "history") {
                LazyColumn(Modifier.fillMaxWidth().fillMaxHeight().padding(top = 2.dp)) {
                    items(c.gitHistory.size) { i ->
                        Text(
                            c.gitHistory[i],
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFC2C9D6),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.weight(0.5f).fillMaxHeight().padding(top = 2.dp)) {
                    items(c.gitStatus.size) { i ->
                        val line = c.gitStatus[i]
                        val xy = line.take(2)
                        val path = line.drop(3).trim()
                        val staged = xy[0] != ' ' && xy[0] != '?'
                        val untracked = xy == "??"
                        val color = when {
                            untracked || xy[0] == 'A' -> Color(0xFF7CE38B)
                            xy.contains('D') -> Color(0xFFFF8A80)
                            xy.contains('M') -> Color(0xFFE0B25C)
                            else -> Color(0xFFC2C9D6)
                        }
                        val selected = c.gitSelected == path
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) Color(0xFF1B202B) else Color.Transparent,
                                    RoundedCornerShape(4.dp),
                                )
                                .clickable { c.gitSelectFile(path) }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(xy, fontSize = 10.sp, color = color, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                path,
                                fontSize = 10.sp,
                                color = Color(0xFFC2C9D6),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (staged) {
                                SmallAction("－") { c.gitUnstage(path) }
                            } else {
                                SmallAction("＋") { c.gitStage(path) }
                            }
                            SmallAction("↺") {
                                c.gitDiscardPath = path
                                c.openDialog("gitDiscard")
                            }
                        }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Stroke))
                Box(
                    Modifier
                        .weight(0.5f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 6.dp, top = 2.dp)
                ) {
                    Text(
                        when {
                            c.gitSelected != null && c.gitDiff.isBlank() -> "（${c.gitSelected} 没有文本差异）"
                            c.gitDiff.isNotBlank() -> c.gitDiff
                            c.gitLastCommit.isNotBlank() -> "最近提交：${c.gitLastCommit}\n\n点左边的文件看 diff；＋ 暂存，－ 取消暂存，↺ 丢弃"
                            else -> "还没有任何提交"
                        },
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF9AA4B2),
                    )
                }
            }
        }
        if (c.gitTab == "changes") {
            HorizontalDivider(color = Stroke)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1B202B),
                    border = BorderStroke(1.dp, Stroke),
                ) {
                    BasicTextField(
                        value = c.gitCommitMsg,
                        onValueChange = { c.gitCommitMsg = it },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = Color(0xFFE6EAF2)),
                        cursorBrush = SolidColor(Color(0xFF4EA1FF)),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                        decorationBox = { inner ->
                            Box(Modifier.heightIn(min = 18.dp)) {
                                if (c.gitCommitMsg.isEmpty()) {
                                    Text("提交信息（提交已暂存的改动）", fontSize = 11.sp, color = Muted)
                                }
                                inner()
                            }
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { c.gitCommit() },
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) {
                    Text("提交", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(24.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, color = Color(0xFF9AA4B2))
    }
}
@Composable
private fun DebugContent(c: IdeController) {
    if (!c.debugActive) {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF141821)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "调试还没接入",
                    fontSize = 12.sp,
                    color = Color(0xFFC2C9D6),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "下一步做串口 gdbstub（run/GdbClient.kt + run/DwarfInfo.kt 已经写好，差接线）",
                    fontSize = 11.sp,
                    color = Muted,
                )
            }
        }
        return
    }
    if (!c.debugPaused) {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF141821)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("正在准备调试…", fontSize = 12.sp, color = Color(0xFFC2C9D6))
                Spacer(Modifier.height(6.dp))
                Text(
                    if (c.debugPrep.isBlank()) c.debugReason else c.debugPrep,
                    fontSize = 11.sp,
                    color = Color(0xFF82AAFF),
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "首次调试需要解压工具链（约 230MB），几十秒内完成",
                    fontSize = 10.sp,
                    color = Muted,
                )
            }
        }
        return
    }

    Row(Modifier.fillMaxSize().background(Color(0xFF141821))) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text("变量", fontSize = 10.sp, color = Muted, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            LazyColumn {
                items(c.debugVars) { pair ->
                    Row {
                        Text(
                            pair.first,
                            fontSize = 11.sp,
                            color = Color(0xFF82AAFF),
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(" = ", fontSize = 11.sp, color = Muted)
                        Text(
                            pair.second,
                            fontSize = 11.sp,
                            color = Color(0xFFC2C9D6),
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (pair.second.trim().matches(Regex("^-?\\d{7,}$"))) {
                            Text("(可能未初始化) ", fontSize = 9.sp, color = Color(0xFF6B7280))
                        }
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Stroke))
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text("调用栈", fontSize = 10.sp, color = Muted, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            LazyColumn {
                items(c.debugStack) { frame ->
                    Text(
                        frame,
                        fontSize = 11.sp,
                        color = Color(0xFFC2C9D6),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBar(c: IdeController) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(Color(0xFF0E1015))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(if (c.dirty) Color(0xFFE0B25C) else Green, RoundedCornerShape(50)))
        Spacer(Modifier.width(6.dp))
        Text(c.statusLine, fontSize = 10.sp, color = Muted, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(c.project?.name ?: "", fontSize = 10.sp, color = Color(0xFF4A5261), maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Text(c.activeLang?.name ?: "—", fontSize = 10.sp, color = Color(0xFF4A5261))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchPanel(c: IdeController, modifier: Modifier = Modifier) {
    Column(modifier.background(Panel).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("全局搜索", fontSize = 11.sp, color = Muted, modifier = Modifier.weight(1f))
            TextButton(onClick = { c.searchOpen = false }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("关闭", fontSize = 10.sp)
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF1B202B),
            border = BorderStroke(1.dp, Stroke),
        ) {
            BasicTextField(
                value = c.searchQuery,
                onValueChange = { c.searchQuery = it },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = Color(0xFFE6EAF2)),
                cursorBrush = SolidColor(Color(0xFF4EA1FF)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                decorationBox = { inner ->
                    Box(Modifier.heightIn(min = 18.dp)) {
                        if (c.searchQuery.isEmpty()) Text("要搜什么？", fontSize = 12.sp, color = Muted)
                        inner()
                    }
                },
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { c.runSearch() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (c.searchBusy) "搜索中…" else "搜索", fontSize = 11.sp)
            }
            TextButton(
                onClick = {
                    c.searchCase = !c.searchCase
                    if (c.searchQuery.isNotBlank()) c.runSearch()
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(if (c.searchCase) "Aa 区分大小写" else "Aa 忽略大小写", fontSize = 10.sp, color = Muted)
            }
        }
        if (c.searchSummary.isNotBlank()) {
            Text(c.searchSummary, fontSize = 10.sp, color = Muted, modifier = Modifier.padding(vertical = 2.dp))
        }
        HorizontalDivider(color = Stroke)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(c.searchResults.size) { i ->
                val h = c.searchResults[i]
                val rel = h.path.removePrefix((c.project?.dir?.absolutePath ?: "") + "/")
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { c.openHit(h) }
                        .padding(vertical = 4.dp, horizontal = 2.dp)
                ) {
                    Text(
                        rel + ":" + h.line,
                        fontSize = 10.sp,
                        color = Color(0xFF4EA1FF),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        h.text,
                        fontSize = 10.sp,
                        color = Color(0xFFC2C9D6),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingStepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = Color(0xFFD7DCE5), modifier = Modifier.weight(1f))
        TextButton(onClick = onMinus, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("−", fontSize = 16.sp) }
        Text(value, fontSize = 12.sp, color = Color(0xFF4EA1FF), fontFamily = FontFamily.Monospace)
        TextButton(onClick = onPlus, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+", fontSize = 16.sp) }
    }
}

@Composable
private fun IdeDialogs(c: IdeController) {
    val d = c.dialog ?: return

    var field by remember(d, c.dialogTarget, c.project?.id) {
        val initial = when (d) {
            "renameNode" -> c.dialogTarget?.name ?: ""
            "renameProject" -> c.project?.name ?: ""
            else -> ""
        }
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    var template by remember(d) { mutableStateOf(if (d == "newProject") Templates.HELLO else Templates.HELLO) }

    when (d) {
        "settings" -> AlertDialog(
            onDismissRequest = { c.closeDialog() },
            title = { Text("设置") },
            text = {
                Column {
                    SettingStepper(
                        "编辑器字号",
                        c.editorFontSize.toString(),
                        { c.changeEditorFontSize(c.editorFontSize - 1) },
                        { c.changeEditorFontSize(c.editorFontSize + 1) },
                    )
                    Text("缩进宽度", fontSize = 12.sp, color = Color(0xFFD7DCE5))
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        listOf(2, 4, 8).forEach { n ->
                            FilterChip(
                                selected = c.editorTabSize == n,
                                onClick = { c.changeTabSize(n) },
                                label = { Text("$n", fontSize = 11.sp) },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("自动换行", fontSize = 12.sp, color = Color(0xFFD7DCE5), modifier = Modifier.weight(1f))
                        Switch(checked = c.editorWordWrap, onCheckedChange = { c.changeWordWrap(it) })
                    }
                    SettingStepper(
                        "终端字号",
                        c.terminalFontSize.toString(),
                        { c.changeTerminalFontSize(c.terminalFontSize - 0.5f) },
                        { c.changeTerminalFontSize(c.terminalFontSize + 0.5f) },
                    )
                }
            },
            confirmButton = { TextButton(onClick = { c.closeDialog() }) { Text("完成") } },
        )

        "gitDiscard" -> AlertDialog(
            onDismissRequest = { c.closeDialog() },
            title = { Text("丢弃改动") },
            text = { Text("确定丢弃「${c.gitDiscardPath}」的改动吗？未跟踪的文件会被删除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    val p = c.gitDiscardPath
                    c.closeDialog()
                    if (p != null) c.gitDiscard(p)
                }) { Text("丢弃", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = { TextButton(onClick = { c.closeDialog() }) { Text("取消") } },
        )

        "chip" -> AlertDialog(
            onDismissRequest = { c.closeDialog() },
            title = { Text("目标芯片") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("芯片决定了用哪套交叉工具链和哪份 sdkconfig。", fontSize = 11.sp, color = Muted)
                    Spacer(Modifier.height(8.dp))
                    c.chips.forEach { id ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    c.chooseChip(id)
                                    c.closeDialog()
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = c.chip == id, onClick = {
                                c.chooseChip(id)
                                c.closeDialog()
                            })
                            Spacer(Modifier.width(4.dp))
                            Text(c.chipLabel(id), fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { c.closeDialog() }) { Text("关闭") } },
        )

        "pack" -> AlertDialog(
            onDismissRequest = { c.closeDialog() },
            title = { Text("ESP-IDF 资源包") },
            text = {
                Column {
                    Text(
                        if (c.packReady) "已安装：" + c.packStatus else "未安装。",
                        fontSize = 12.sp,
                        color = if (c.packReady) Green else Color(0xFFFFAB91),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "资源包是一个 zip，里面装着 ESP-IDF 源码、xtensa-esp-elf 交叉工具链、"
                            + "cmake/ninja、python 和 glibc 运行时。导入后全部离线可用。",
                        fontSize = 11.sp,
                        color = Muted,
                    )
                    if (c.packBusy) {
                        Spacer(Modifier.height(8.dp))
                        Text(c.packStatus, fontSize = 11.sp, color = Accent)
                    }
                }
            },
            confirmButton = {
                Row {
                    val side = c.externalPackFile()
                    if (side != null) {
                        TextButton(onClick = {
                            c.closeDialog()
                            c.importSideLoadedPack()
                        }) { Text("导入 pack.zip") }
                    }
                    TextButton(onClick = {
                        c.closeDialog()
                        c.pickPack()
                    }) { Text("选择 zip…") }
                }
            },
            dismissButton = {
                TextButton(onClick = { c.closeDialog() }) { Text("关闭") }
            },
        )

        "about" -> AlertDialog(
            onDismissRequest = { c.closeDialog() },
            title = { Text("关于 CodePad") },
            text = {
                Column {
                    Text("EspForge ${c.appVersion}", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Android 平板上的 ESP32 IDE：\n" +
                            "· 官方 ESP-IDF 工程结构（CMake + idf.py）\n" +
                            "· 内置 xtensa 交叉工具链，离线编译\n" +
                            "· USB 烧录 + 串口监视 + gdbstub 断点调试\n" +
                            "· 芯片支持：ESP32 / ESP32-S3",
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { c.closeDialog() }) { Text("好") } },
        )

        "deleteProject" -> AlertDialog(
            onDismissRequest = { c.closeDialog() },
            title = { Text("删除工程") },
            text = { Text("确定删除工程「${c.project?.name}」及其全部文件吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    c.deleteCurrentProject()
                    c.closeDialog()
                }) { Text("删除", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = { TextButton(onClick = { c.closeDialog() }) { Text("取消") } },
        )

        "deleteNode" -> AlertDialog(
            onDismissRequest = { c.closeDialog() },
            title = { Text("删除") },
            text = {
                val target = c.dialogTarget
                Text(
                    if (target?.isDirectory == true)
                        "确定删除文件夹「${target.name}」及其中所有文件吗？"
                    else
                        "确定删除「${target?.name ?: ""}」吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    c.dialogTarget?.let { c.deleteNode(it) }
                    c.closeDialog()
                }) { Text("删除", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = { TextButton(onClick = { c.closeDialog() }) { Text("取消") } },
        )

        "newProject" -> AlertDialog(
            onDismissRequest = { c.closeDialog() },
            title = { Text("新建工程") },
            text = {
                Column {
                    OutlinedTextField(
                        value = field,
                        onValueChange = { field = it },
                        singleLine = true,
                        label = { Text("工程名称") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("模板", fontSize = 11.sp, color = Muted)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Templates.all.forEach { (id, label, _) ->
                            TemplateChip(label, template == id) { template = id }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    c.createProject(field.text, template)
                    c.closeDialog()
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { c.closeDialog() }) { Text("取消") } },
        )

        "renameProject" -> AlertDialog(
            onDismissRequest = { c.closeDialog() },
            title = { Text("重命名工程") },
            text = {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    singleLine = true,
                    label = { Text("工程名称") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    c.renameCurrentProject(field.text)
                    c.closeDialog()
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { c.closeDialog() }) { Text("取消") } },
        )

        "moveNode" -> {
            val root = c.project?.dir
            val folders = c.allFolders()
            var selected by remember(d, c.dialogTarget, c.project?.id) {
                mutableStateOf(
                    folders.firstOrNull { it != c.dialogTarget?.parentFile } ?: folders.firstOrNull()
                )
            }
            AlertDialog(
                onDismissRequest = { c.closeDialog() },
                title = { Text("移动到文件夹") },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        folders.forEach { f ->
                            val label = if (f == root) "/ · 工程根目录" else Workspace.relativePath(root ?: f, f)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = f }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected == f, onClick = { selected = f })
                                Spacer(Modifier.width(4.dp))
                                Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val dst = selected
                        val src = c.dialogTarget
                        if (dst != null && src != null) c.moveNode(src, dst)
                        c.closeDialog()
                    }) { Text("移动") }
                },
                dismissButton = { TextButton(onClick = { c.closeDialog() }) { Text("取消") } },
            )
        }

        else -> {
            val title = when (d) {
                "newFile" -> "新建文件"
                "newFolder" -> "新建文件夹"
                "renameNode" -> "重命名"
                else -> "输入"
            }
            val label = when (d) {
                "newFolder" -> "文件夹名称"
                else -> "名称"
            }
            AlertDialog(
                onDismissRequest = { c.closeDialog() },
                title = { Text(title) },
                text = {
                    OutlinedTextField(
                        value = field,
                        onValueChange = { field = it },
                        singleLine = true,
                        label = { Text(label) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            applyNodeDialog(c, d, field.text)
                        }),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { applyNodeDialog(c, d, field.text) }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { c.closeDialog() }) { Text("取消") } },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 11.sp) })
}

private fun applyNodeDialog(c: IdeController, dialog: String, text: String) {
    when (dialog) {
        "newFile" -> c.createNode(c.dialogTarget ?: c.project?.dir ?: return, text, false)
        "newFolder" -> c.createNode(c.dialogTarget ?: c.project?.dir ?: return, text, true)
        "renameNode" -> c.dialogTarget?.let { c.renameNode(it, text) }
    }
    c.closeDialog()
}