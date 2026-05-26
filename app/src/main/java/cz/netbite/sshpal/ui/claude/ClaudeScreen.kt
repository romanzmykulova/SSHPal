package cz.netbite.sshpal.ui.claude

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaudeScreen(viewModel: ClaudeViewModel) {
    val state by viewModel.state.collectAsState()

    // Auto-launch remote-control when the user lands on Claude tab with an
    // active session and no resolved URL yet. The launch is idempotent —
    // startRemoteControl cancels any previous job before kicking off a new one.
    LaunchedEffect(state) {
        if (state is ClaudePaneState.Idle) {
            viewModel.startRemoteControl()
        }
    }

    when (val s = state) {
        ClaudePaneState.NoSession -> EmptyMessage(
            title = "No active session",
            body = "Connect a workspace on the Workspaces tab, then come back here.",
        )
        ClaudePaneState.Idle -> ConnectingView(
            log = "",
            onRetry = viewModel::startRemoteControl,
            kicking = false,
        )
        is ClaudePaneState.Connecting -> ConnectingView(
            log = s.log,
            onRetry = viewModel::startRemoteControl,
            kicking = true,
        )
        is ClaudePaneState.Loaded -> LoadedView(url = s.url, onReset = viewModel::reset)
        is ClaudePaneState.Failed -> FailedView(
            message = s.message,
            log = s.log,
            onRetry = viewModel::startRemoteControl,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectingView(log: String, onRetry: () -> Unit, kicking: Boolean) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (kicking) "Starting Claude remote-control…" else "Claude") },
                actions = {
                    if (!kicking) {
                        OutlinedButton(onClick = onRetry) { Text("Start") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (kicking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Running `claude` over SSH, then sending /remote-control. " +
                        "First match of https://… in the output is opened as the chat URL.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "No remote-control URL yet. Tap Start above to launch `claude` " +
                        "on the server and capture the chat URL.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LogPane(log = log)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FailedView(message: String, log: String, onRetry: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Claude — failed") },
                actions = {
                    OutlinedButton(onClick = onRetry) { Text("Retry") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Full Claude output:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LogPane(log = log)
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

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoadedView(url: String, onReset: () -> Unit) {
    val context = LocalContext.current
    var progress by remember { mutableStateOf(0) }
    var currentUrl by remember(url) { mutableStateOf(url) }
    var canGoBack by remember { mutableStateOf(false) }

    val webView = remember(url) {
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
        }
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, true)
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, newProgress: Int) {
                progress = newProgress
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: return false
                v?.loadUrl(target)
                currentUrl = target
                return true
            }

            override fun doUpdateVisitedHistory(v: WebView?, u: String?, isReload: Boolean) {
                if (u != null) currentUrl = u
                canGoBack = v?.canGoBack() == true
            }
        }
        view.loadUrl(url)
        view
    }

    DisposableEffect(webView) {
        onDispose {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
    }

    BackHandler(enabled = canGoBack) {
        webView.goBack()
        canGoBack = webView.canGoBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        currentUrl,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = {
                            webView.goBack()
                            canGoBack = webView.canGoBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { webView.loadUrl(url) }) {
                        Icon(Icons.Default.Home, contentDescription = "Home")
                    }
                    IconButton(onClick = { webView.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                    IconButton(onClick = onReset) {
                        Icon(Icons.Default.RestartAlt, contentDescription = "Re-run remote-control")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { webView },
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
}
