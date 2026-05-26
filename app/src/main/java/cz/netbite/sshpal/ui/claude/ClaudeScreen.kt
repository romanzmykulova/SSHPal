package cz.netbite.sshpal.ui.claude

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView

const val DEFAULT_CLAUDE_URL = "https://app.iwantteam.ai/"

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ClaudeScreen(homeUrl: String = DEFAULT_CLAUDE_URL) {
    val context = LocalContext.current
    var progress by remember { mutableStateOf(0) }
    var currentUrl by remember { mutableStateOf(homeUrl) }
    var canGoBack by remember { mutableStateOf(false) }

    val webView = remember {
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
                val url = request?.url?.toString() ?: return false
                v?.loadUrl(url)
                currentUrl = url
                return true
            }

            override fun doUpdateVisitedHistory(v: WebView?, url: String?, isReload: Boolean) {
                if (url != null) currentUrl = url
                canGoBack = v?.canGoBack() == true
            }
        }
        view.loadUrl(homeUrl)
        view
    }

    // Reload when the active workspace's claudeUrl changes (user picked a
    // different workspace whose claudeUrl differs from the one currently
    // shown). Skip the initial composition: the WebView already loaded
    // homeUrl above.
    var lastLoadedHome by remember { mutableStateOf(homeUrl) }
    LaunchedEffect(homeUrl) {
        if (homeUrl != lastLoadedHome) {
            webView.loadUrl(homeUrl)
            lastLoadedHome = homeUrl
        }
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
                    IconButton(onClick = {
                        webView.loadUrl(homeUrl)
                    }) {
                        Icon(Icons.Default.Home, contentDescription = "Home")
                    }
                    IconButton(onClick = { webView.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
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
