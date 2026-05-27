package cz.netbite.sshpal.ui.claude

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ClaudeScreen(viewModel: ClaudeViewModel) {
    val state by viewModel.state.collectAsState()

    when (val s = state) {
        ClaudePaneState.NoSession -> EmptyMessage(
            title = "No active session",
            body = "Connect a workspace on the Workspaces tab, then come back here.",
        )
        is ClaudePaneState.Loaded -> LoadedPane(state = s, viewModel = viewModel)
    }
}

// ---------- Loaded pane ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadedPane(state: ClaudePaneState.Loaded, viewModel: ClaudeViewModel) {
    val selectedTab = state.tabs.firstOrNull { it.id == state.selectedId }

    // Per-tab WebView cache. Lives across tab switches so chat state survives.
    // Keyed by tabId; entry is (currentUrl, WebView). When the URL for a tab
    // changes (after Restart), the old WebView is destroyed and a fresh one
    // is created on next render.
    val context = LocalContext.current
    val webViews = remember { mutableMapOf<Long, Pair<String, WebView>>() }

    // GC: destroy WebViews for tabs no longer in state, OR for tabs that have
    // dropped out of Ready (so their URL is dead). Runs on every state change.
    DisposableEffect(state.tabs) {
        val keep: Map<Long, String> = state.tabs
            .mapNotNull { (it as? TabState.Ready)?.let { r -> r.id to r.url } }
            .toMap()
        val stale = webViews.entries.filter { (id, pair) ->
            keep[id] != pair.first
        }.map { it.key }
        stale.forEach { id ->
            webViews.remove(id)?.second?.let { wv ->
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.destroy()
            }
        }
        onDispose {
            // Final cookie flush before tearing down the WebViews so the
            // Anthropic session survives "swipe app away" / process kill.
            CookieManager.getInstance().flush()
            webViews.values.forEach { (_, wv) ->
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.destroy()
            }
            webViews.clear()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TabTopBar(
                selectedTab = selectedTab,
                onCancel = { selectedTab?.let { viewModel.cancelHandshake(it.id) } },
                onRetry = { selectedTab?.let { viewModel.restartTab(it.id) } },
                onRestart = { selectedTab?.let { viewModel.restartTab(it.id) } },
                onHome = { selectedTab?.let { it as? TabState.Ready }?.let { ready ->
                    webViews[ready.id]?.second?.loadUrl(ready.url)
                } },
                onReload = { selectedTab?.let { it as? TabState.Ready }?.let { ready ->
                    webViews[ready.id]?.second?.reload()
                } },
                onOpenExternal = { selectedTab?.let { it as? TabState.Ready }?.let { ready ->
                    openInExternalBrowser(context, ready.url)
                } },
                onGoToUrl = { target -> selectedTab?.let { it as? TabState.Ready }?.let { ready ->
                    webViews[ready.id]?.second?.loadUrl(target)
                } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.tabs.isNotEmpty()) {
                ClaudeTabStrip(
                    tabs = state.tabs,
                    selectedId = state.selectedId,
                    onSelect = viewModel::selectTab,
                    onClose = viewModel::closeTab,
                    onRename = viewModel::renameTab,
                    onNew = viewModel::newSession,
                )
                HorizontalDivider()
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    null -> EmptyTabsPane(onNew = viewModel::newSession)
                    is TabState.Ready -> TabContentReady(
                        tab = selectedTab,
                        webViews = webViews,
                        context = context,
                    )
                    is TabState.Connecting -> TabContentConnecting(tab = selectedTab)
                    is TabState.Failed -> TabContentFailed(tab = selectedTab)
                }
            }
        }
    }
}

// ---------- Top bar ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabTopBar(
    selectedTab: TabState?,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRestart: () -> Unit,
    onHome: () -> Unit,
    onReload: () -> Unit,
    onOpenExternal: () -> Unit,
    onGoToUrl: (String) -> Unit,
) {
    var urlEditorOpen by remember { mutableStateOf(false) }
    val currentUrl = (selectedTab as? TabState.Ready)?.url

    TopAppBar(
        title = {
            when (selectedTab) {
                is TabState.Ready -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { urlEditorOpen = true }
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        selectedTab.url,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                is TabState.Connecting -> Text("Starting Claude remote-control…")
                is TabState.Failed -> Text("Claude — failed")
                null -> Text("Claude")
            }
        },
        actions = {
            when (selectedTab) {
                is TabState.Ready -> {
                    IconButton(onClick = onOpenExternal) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in external browser")
                    }
                    IconButton(onClick = onHome) {
                        Icon(Icons.Default.Home, contentDescription = "Home")
                    }
                    IconButton(onClick = onReload) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                    TextButton(onClick = onRestart) { Text("Restart") }
                }
                is TabState.Connecting -> {
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
                is TabState.Failed -> {
                    OutlinedButton(onClick = onRetry) { Text("Retry") }
                }
                null -> {}
            }
        },
    )

    if (urlEditorOpen && currentUrl != null) {
        UrlEditDialog(
            initial = currentUrl,
            onDismiss = { urlEditorOpen = false },
            onGo = { target ->
                onGoToUrl(target)
                urlEditorOpen = false
            },
            onOpenExternal = { _ ->
                onOpenExternal()
                urlEditorOpen = false
            },
        )
    }
}

// ---------- Tab strip ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClaudeTabStrip(
    tabs: List<TabState>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onNew: () -> Unit,
) {
    val selectedIndex = tabs.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    var renameTarget by remember { mutableStateOf<TabState?>(null) }
    var menuOpenFor by remember { mutableStateOf<Long?>(null) }

    // No combinedClickable inside Tab — the recent Compose rewrite of that
    // modifier crashes when nested under an Indication-providing parent
    // (which Tab is). Each tab carries an explicit overflow IconButton
    // instead: tap label = select, tap ⋮ = open Rename / Close menu.
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 0.dp,
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = tab.id == selectedId,
                onClick = { onSelect(tab.id) },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TabStatusDot(tab)
                        Text(
                            tab.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Box {
                            IconButton(
                                onClick = { menuOpenFor = tab.id },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "Tab menu",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpenFor == tab.id,
                                onDismissRequest = { menuOpenFor = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = {
                                        renameTarget = tab
                                        menuOpenFor = null
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Close") },
                                    onClick = {
                                        onClose(tab.id)
                                        menuOpenFor = null
                                    },
                                )
                            }
                        }
                    }
                },
            )
        }
        // Trailing "+" pseudo-tab.
        Tab(
            selected = false,
            onClick = onNew,
            text = {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "New session",
                    modifier = Modifier.size(20.dp),
                )
            },
        )
    }

    if (renameTarget != null) {
        val target = renameTarget!!
        RenameDialog(
            initial = target.label,
            onDismiss = { renameTarget = null },
            onConfirm = { newLabel ->
                onRename(target.id, newLabel)
                renameTarget = null
            },
        )
    }
}

@Composable
private fun TabStatusDot(tab: TabState) {
    val color = when (tab) {
        is TabState.Ready -> MaterialTheme.colorScheme.primary
        is TabState.Connecting -> MaterialTheme.colorScheme.tertiary
        is TabState.Failed -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

// ---------- Tab content panes ----------

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TabContentReady(
    tab: TabState.Ready,
    webViews: MutableMap<Long, Pair<String, WebView>>,
    context: Context,
) {
    var progress by remember(tab.id) { mutableStateOf(0) }
    var canGoBack by remember(tab.id) { mutableStateOf(false) }

    val webView = remember(tab.id, tab.url) {
        val cached = webViews[tab.id]
        if (cached != null && cached.first == tab.url) {
            cached.second
        } else {
            cached?.second?.let { old ->
                (old.parent as? ViewGroup)?.removeView(old)
                old.stopLoading()
                old.destroy()
            }
            val fresh = createWebView(context, tab.url) { newProgress ->
                progress = newProgress
            }
            fresh.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url?.toString() ?: return false
                    v?.loadUrl(target)
                    return true
                }
                override fun doUpdateVisitedHistory(v: WebView?, u: String?, isReload: Boolean) {
                    canGoBack = v?.canGoBack() == true
                }
                override fun onPageFinished(v: WebView?, url: String?) {
                    // Flush cookies to disk on every page-finish so the
                    // Anthropic login cookie survives an app kill (default
                    // flush only happens on app backgrounding).
                    CookieManager.getInstance().flush()
                }
            }
            fresh.loadUrl(tab.url)
            webViews[tab.id] = tab.url to fresh
            fresh
        }
    }

    BackHandler(enabled = canGoBack) {
        webView.goBack()
        canGoBack = webView.canGoBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                // The cached WebView may still be attached to a previous host
                // ViewGroup from before a tab switch. Detach before reattaching.
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun TabContentConnecting(tab: TabState.Connecting) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            "Running `claude` in ${tab.cwd}, then sending /remote-control. " +
                "First match of https://… in the output is opened as the chat URL.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LogPane(log = tab.log)
    }
}

@Composable
private fun TabContentFailed(tab: TabState.Failed) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            tab.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "Full Claude output:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LogPane(log = tab.log)
    }
}

@Composable
private fun EmptyTabsPane(onNew: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("No Claude sessions yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tap to start one.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onNew) { Text("New session") }
        }
    }
}

@Composable
private fun LogPane(log: String) {
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(vScroll),
    ) {
        Text(
            text = if (log.isBlank()) "(no output yet)" else log,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(hScroll)
                .padding(bottom = 24.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            softWrap = false,
        )
    }
}

@Composable
private fun EmptyMessage(title: String, body: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------- Dialogs ----------

@Composable
private fun UrlEditDialog(
    initial: String,
    onDismiss: () -> Unit,
    onGo: (String) -> Unit,
    onOpenExternal: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to URL") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = { onGo(normalizeUrl(text)) },
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        },
        confirmButton = {
            Button(onClick = { onGo(normalizeUrl(text)) }) { Text("Open here") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onOpenExternal(normalizeUrl(text)) }) { Text("Open in browser") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ---------- Helpers ----------

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: Context,
    initialUrl: String,
    onProgress: (Int) -> Unit,
): WebView {
    val view = WebView(context)
    view.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        builtInZoomControls = true
        displayZoomControls = false
        mediaPlaybackRequiresUserGesture = false
        cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        allowFileAccess = false
        allowContentAccess = false
        // Anthropic login uses Google OAuth via `window.open` popup;
        // WebView blocks those by default, leaving the page blank.
        // Enable popups and handle them via onCreateWindow below.
        setSupportMultipleWindows(true)
        javaScriptCanOpenWindowsAutomatically = true
        // claude.ai detects "in-app browser" UA strings and refuses
        // the login flow. Pretend to be modern mobile Chrome — same
        // engine the WebView actually runs on Android 13+ anyway.
        userAgentString = "Mozilla/5.0 (Linux; Android 13; SSHPal) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
    val cookies = CookieManager.getInstance()
    cookies.setAcceptCookie(true)
    cookies.setAcceptThirdPartyCookies(view, true)
    view.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(v: WebView?, newProgress: Int) {
            onProgress(newProgress)
        }

        // OAuth popups: route the popup target URL back into the same
        // WebView. Without this override, window.open returns null and
        // the login form silently bails.
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message?,
        ): Boolean {
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            val popup = WebView(view!!.context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                        val target = request?.url?.toString() ?: return false
                        // Load the popup's target URL in the parent WebView
                        // — keeps us in one window with one cookie store.
                        view.loadUrl(target)
                        return true
                    }
                }
            }
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }

        // JavaScript console output goes to logcat (tag SshPalWebView)
        // so the smoke-test workflow and adb logcat can see what the
        // page is actually doing — invaluable when "white screen"
        // happens on a real device.
        override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
            if (message != null) {
                android.util.Log.d(
                    "SshPalWebView",
                    "[${message.messageLevel()}] ${message.message()} " +
                        "(${message.sourceId()}:${message.lineNumber()})",
                )
            }
            return true
        }
    }
    return view
}

private fun normalizeUrl(raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.contains("://")) trimmed else "https://$trimmed"
}

private fun openInExternalBrowser(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
