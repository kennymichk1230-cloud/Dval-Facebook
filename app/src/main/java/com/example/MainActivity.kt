package com.example

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    // WebView and Upload Callbacks
    private var webViewInstance: WebView? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoPath: String? = null

    // Launchers for Runtime Permissions and File Chooser
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Permission states are processed by the web chrome client dynamically
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            if (data == null || data.data == null) {
                // Photo captured by Camera
                cameraPhotoPath?.let { arrayOf(Uri.parse(it)) }
            } else {
                val dataString = data.dataString
                if (dataString != null) {
                    arrayOf(Uri.parse(dataString))
                } else {
                    null
                }
            }
        } else {
            null
        }

        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Full Edge-to-Edge immersion
        enableEdgeToEdge()

        // Request permissions on startup for smooth experience
        requestRequiredPermissions()

        setContent {
            MyApplicationTheme {
                var showSplash by remember { mutableStateOf(true) }
                var isLoading by remember { mutableStateOf(false) }
                var errorOccurred by remember { mutableStateOf(false) }
                var canGoBack by remember { mutableStateOf(false) }
                var isDesktopSite by remember { mutableStateOf(false) }
                var showMenu by remember { mutableStateOf(false) }
                var currentUrl by remember { mutableStateOf("https://m.facebook.com") }

                // Dismiss splash screen after 2.5s
                LaunchedEffect(Unit) {
                    delay(2500)
                    showSplash = false
                }

                // Handle system Back button inside the WebView before exiting the app
                BackHandler(enabled = canGoBack && !showSplash) {
                    if (showMenu) {
                        showMenu = false
                    } else {
                        webViewInstance?.goBack()
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF1C1B1F), // Match Geometric Balance background
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        // 1. Geometric Header (matches header tag class, bg-[#1C1B1F])
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .background(Color(0xFF1C1B1F))
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Hamburger menu/options button
                            IconButton(
                                onClick = { showMenu = !showMenu },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Menu Drawer",
                                    tint = Color(0xFFE6E1E5)
                                )
                            }

                            // Address/Status container (matches div classbg-[#49454F] rounded-2xl)
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .padding(horizontal = 8.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF49454F))
                                    .border(
                                        1.dp,
                                        if (showMenu) Color(0xFFD0BCFF) else Color.Transparent,
                                        RoundedCornerShape(16.dp)
                                    )
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Secure Lock",
                                    tint = Color(0xFFCAC4D0),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isDesktopSite) "m.facebook.com (Desktop)" else "m.facebook.com",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Color(0xFFCAC4D0),
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }

                            // Refresh button
                            IconButton(
                                onClick = { webViewInstance?.reload() },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh Page",
                                    tint = Color(0xFFE6E1E5)
                                )
                            }
                        }

                        // Thin custom loading line (matches h-1 class)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(Color(0xFF1C1B1F))
                        ) {
                            if (isLoading && !showSplash) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFFD0BCFF),
                                    trackColor = Color.Transparent
                                )
                            }
                        }

                        // 2. Main WebView view area
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            WebViewScreen(
                                initialUrl = currentUrl,
                                isDesktopSite = isDesktopSite,
                                onPageStarted = { url ->
                                    isLoading = true
                                    errorOccurred = false
                                    currentUrl = url
                                },
                                onPageFinished = { url ->
                                    isLoading = false
                                    currentUrl = url
                                    canGoBack = webViewInstance?.canGoBack() ?: false
                                    CookieManager.getInstance().flush()
                                },
                                onReceivedError = {
                                    errorOccurred = true
                                    isLoading = false
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            // Network recovery/offline dialog overlay
                            if (errorOccurred) {
                                NetworkErrorView(
                                    onRetry = {
                                        errorOccurred = false
                                        isLoading = true
                                        webViewInstance?.reload()
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // Custom animated settings overlay panel
                            androidx.compose.animation.AnimatedVisibility(
                                visible = showMenu,
                                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                SettingsOverlayMenu(
                                    isDesktopSite = isDesktopSite,
                                    onToggleDesktopSite = { isDesktopSite = !isDesktopSite },
                                    onClearCache = {
                                        webViewInstance?.clearCache(true)
                                        val cookieManager = CookieManager.getInstance()
                                        cookieManager.removeAllCookies(null)
                                        cookieManager.flush()
                                        Toast.makeText(this@MainActivity, "Storage optimized!", Toast.LENGTH_SHORT).show()
                                    },
                                    onClose = { showMenu = false },
                                    canGoBack = canGoBack,
                                    canGoForward = webViewInstance?.canGoForward() ?: false,
                                    onGoBack = { webViewInstance?.goBack() },
                                    onGoForward = { webViewInstance?.goForward() }
                                )
                            }
                        }

                        // 3. Geometric Bottom Navigation (matches bottom nav class bg-[#2B2930])
                        GeometricBottomNav(
                            currentUrl = currentUrl,
                            showMenu = showMenu,
                            onTabSelected = { tab ->
                                showMenu = false
                                when (tab) {
                                    "home" -> webViewInstance?.loadUrl("https://m.facebook.com/")
                                    "video" -> webViewInstance?.loadUrl("https://m.facebook.com/watch/")
                                    "alerts" -> webViewInstance?.loadUrl("https://m.facebook.com/notifications/")
                                    "menu" -> showMenu = true
                                }
                            }
                        )
                    }

                    // Splash Overlay
                    AnimatedVisibility(
                        visible = showSplash,
                        exit = fadeOut(animationSpec = tween(1000)) + shrinkOut(
                            shrinkTowards = Alignment.Center,
                            animationSpec = tween(1000)
                        )
                    ) {
                        SplashScreenView(modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webViewInstance?.let {
            it.stopLoading()
            it.destroy()
        }
        webViewInstance = null
        super.onDestroy()
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            @Suppress("DEPRECATION")
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            @Suppress("DEPRECATION")
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(neededPermissions.toTypedArray())
        }
    }

    @Composable
    fun WebViewScreen(
        initialUrl: String,
        isDesktopSite: Boolean,
        onPageStarted: (String) -> Unit,
        onPageFinished: (String) -> Unit,
        onReceivedError: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val context = LocalContext.current
        val isDarkTheme = isSystemInDarkTheme()

        AndroidView(
            factory = { ctx ->
                SwipeRefreshLayout(ctx).apply {
                    setColorSchemeColors(android.graphics.Color.parseColor("#D0BCFF"))
                    
                    val webView = WebView(ctx).apply {
                        webViewInstance = this
                        
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            setSupportZoom(true)
                            allowFileAccess = true
                            allowContentAccess = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            cacheMode = WebSettings.LOAD_DEFAULT

                            javaScriptCanOpenWindowsAutomatically = false
                            supportMultipleWindows()
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            settings.forceDark = if (isDarkTheme) {
                                WebSettings.FORCE_DARK_ON
                            } else {
                                WebSettings.FORCE_DARK_OFF
                            }
                        }

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                url?.let { onPageStarted(it) }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isRefreshing = false
                                url?.let { onPageFinished(it) }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                if (url.contains("facebook.com") || url.contains("messenger.com") ||
                                    url.contains("fb.com") || url.contains("fb.me")
                                ) {
                                    view?.loadUrl(url)
                                    return true
                                }

                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    ctx.startActivity(intent)
                                    return true
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "Cannot open external link", Toast.LENGTH_SHORT).show()
                                    return false
                                }
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val url = request?.url?.toString() ?: return null
                                val blockList = listOf(
                                    "doubleclick.net",
                                    "google-analytics.com",
                                    "googlesyndication.com",
                                    "adnxs.com",
                                    "rubiconproject.com",
                                    "adservice.google"
                                )
                                for (blocked in blockList) {
                                    if (url.contains(blocked)) {
                                        return WebResourceResponse("text/plain", "utf-8", null)
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    onReceivedError()
                                }
                            }

                            @Suppress("DEPRECATION")
                            override fun onReceivedError(
                                view: WebView?,
                                errorCode: Int,
                                description: String?,
                                failingUrl: String?
                            ) {
                                if (failingUrl == initialUrl) {
                                    onReceivedError()
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onPermissionRequest(request: PermissionRequest) {
                                val resources = request.resources
                                val hasCamera = ContextCompat.checkSelfPermission(
                                    ctx, Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                                val hasMic = ContextCompat.checkSelfPermission(
                                    ctx, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED

                                val permissionsToGrant = mutableListOf<String>()
                                for (res in resources) {
                                    if (res == PermissionRequest.RESOURCE_VIDEO_CAPTURE && hasCamera) {
                                        permissionsToGrant.add(res)
                                    }
                                    if (res == PermissionRequest.RESOURCE_AUDIO_CAPTURE && hasMic) {
                                        permissionsToGrant.add(res)
                                    }
                                }

                                if (permissionsToGrant.isNotEmpty()) {
                                    request.grant(permissionsToGrant.toTypedArray())
                                } else {
                                    request.deny()
                                }
                            }

                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                this@MainActivity.filePathCallback?.onReceiveValue(null)
                                this@MainActivity.filePathCallback = filePathCallback

                                val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                                if (takePictureIntent.resolveActivity(packageManager) != null) {
                                    val photoFile = try {
                                        createImageFile()
                                    } catch (ex: IOException) {
                                        null
                                    }
                                    if (photoFile != null) {
                                        val photoURI = FileProvider.getUriForFile(
                                            this@MainActivity,
                                            "${packageName}.fileprovider",
                                            photoFile
                                        )
                                        cameraPhotoPath = "file:" + photoFile.absolutePath
                                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                                    }
                                }

                                val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "*/*"
                                }

                                val intentArray: Array<Intent?> = if (takePictureIntent.resolveActivity(packageManager) != null) {
                                    arrayOf(takePictureIntent)
                                } else {
                                    emptyArray()
                                }

                                val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                                    putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                                    putExtra(Intent.EXTRA_TITLE, "Upload Photo or File")
                                    putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray)
                                }

                                fileChooserLauncher.launch(chooserIntent)
                                return true
                            }

                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?
                            ): Boolean {
                                val transport = resultMsg?.obj as? WebView.WebViewTransport
                                if (transport != null) {
                                    transport.webView = view
                                    resultMsg.sendToTarget()
                                    return true
                                }
                                return false
                            }
                        }

                        setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                            try {
                                val request = DownloadManager.Request(Uri.parse(url)).apply {
                                    setMimeType(mimetype)
                                    val cookies = CookieManager.getInstance().getCookie(url)
                                    addRequestHeader("cookie", cookies)
                                    addRequestHeader("User-Agent", userAgent)
                                    setDescription("Downloading file...")
                                    setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    setDestinationInExternalPublicDir(
                                        Environment.DIRECTORY_DOWNLOADS,
                                        URLUtil.guessFileName(url, contentDisposition, mimetype)
                                    )
                                }

                                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                dm.enqueue(request)
                                Toast.makeText(ctx, "Starting download...", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }

                        loadUrl(initialUrl)
                    }

                    addView(webView)
                    setOnRefreshListener {
                        webView.reload()
                    }
                }
            },
            update = { swipeLayout ->
                val webView = swipeLayout.getChildAt(0) as? WebView
                webView?.settings?.apply {
                    if (isDesktopSite) {
                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
                    } else {
                        userAgentString = null
                    }
                }
            },
            modifier = modifier
        )
    }

    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File = getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        return File.createTempFile(
            "JPEG_${timeStamp}_",
            ".jpg",
            storageDir
        )
    }
}

/**
 * Geometric Bottom Navigation matching exact HTML spec color guidelines
 */
@Composable
fun GeometricBottomNav(
    currentUrl: String,
    showMenu: Boolean,
    onTabSelected: (String) -> Unit
) {
    val activeTab = when {
        showMenu -> "menu"
        currentUrl.contains("watch") || currentUrl.contains("video") -> "video"
        currentUrl.contains("notifications") || currentUrl.contains("message") -> "alerts"
        else -> "home"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Color(0xFF2B2930))
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            Triple("home", Icons.Default.Home, "Home"),
            Triple("video", Icons.Default.PlayArrow, "Video"),
            Triple("alerts", Icons.Default.Notifications, "Alerts"),
            Triple("menu", Icons.Default.Menu, "Menu")
        ).forEach { (id, icon, label) ->
            val isActive = activeTab == id
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTabSelected(id) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Pill highlight container matching active classes in design HTML
                Box(
                    modifier = Modifier
                        .width(52.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isActive) Color(0xFFEADDFF) else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (isActive) Color(0xFF21005D) else Color(0xFFE6E1E5).copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = Color(0xFFE6E1E5),
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp
                    ),
                    modifier = Modifier.graphicsLayer { alpha = if (isActive) 1.0f else 0.6f }
                )
            }
        }
    }
}

/**
 * Geometric Balance Settings Menu Drawer Overlay (at the top)
 */
@Composable
fun SettingsOverlayMenu(
    isDesktopSite: Boolean,
    onToggleDesktopSite: () -> Unit,
    onClearCache: () -> Unit,
    onClose: () -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onClose)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clickable(enabled = false, onClick = {})
                .align(Alignment.TopCenter),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Lite Settings",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE6E1E5),
                            letterSpacing = 0.5.sp
                        )
                    )
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Menu",
                            tint = Color(0xFFE6E1E5)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Navigation Row (Back / Forward)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onGoBack,
                        enabled = canGoBack,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF49454F),
                            contentColor = Color(0xFFE6E1E5),
                            disabledContainerColor = Color(0xFF49454F).copy(alpha = 0.4f),
                            disabledContentColor = Color(0xFFE6E1E5).copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Go Back",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Back", fontSize = 13.sp)
                    }

                    Button(
                        onClick = onGoForward,
                        enabled = canGoForward,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF49454F),
                            contentColor = Color(0xFFE6E1E5),
                            disabledContainerColor = Color(0xFF49454F).copy(alpha = 0.4f),
                            disabledContentColor = Color(0xFFE6E1E5).copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Forward", fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = "Go Forward",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color(0xFF49454F))
                Spacer(modifier = Modifier.height(12.dp))

                // Toggle User Agent (Mobile / Desktop Viewport)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onToggleDesktopSite)
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isDesktopSite) Icons.Default.PhoneAndroid else Icons.Default.Computer,
                            contentDescription = "Viewport Toggle",
                            tint = Color(0xFFD0BCFF),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Request Desktop Site",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE6E1E5)
                                )
                            )
                            Text(
                                text = if (isDesktopSite) "Desktop active" else "Mobile active",
                                style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFCAC4D0))
                            )
                        }
                    }
                    Switch(
                        checked = isDesktopSite,
                        onCheckedChange = { onToggleDesktopSite() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF21005D),
                            checkedTrackColor = Color(0xFFD0BCFF),
                            uncheckedThumbColor = Color(0xFFCAC4D0),
                            uncheckedTrackColor = Color(0xFF49454F)
                        )
                    )
                }

                // Clear storage & speed up optimization
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onClearCache)
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Optimize storage",
                        tint = Color(0xFFD0BCFF),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Optimize Storage",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFE6E1E5)
                            )
                        )
                        Text(
                            text = "Clears persistent page caches",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFCAC4D0))
                        )
                    }
                }

                // Info card details
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "About details",
                        tint = Color(0xFFD0BCFF),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Dval Facebook Lite",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFE6E1E5)
                            )
                        )
                        Text(
                            text = "Fast lightweight browser engine.",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFCAC4D0))
                        )
                    }
                }
            }
        }
    }
}

/**
 * Modern Custom-Designed Splash Screen showing Facebook Logo and Meta banner
 */
@Composable
fun SplashScreenView(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_logo")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_scale"
    )

    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A), // Slate Dark
                        Color(0xFF020617)  // Pitch Black
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxHeight()
        ) {
            Spacer(modifier = Modifier.weight(1.2f))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(110.dp * pulseScale)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B))
                    .border(2.dp, Brush.radialGradient(listOf(Color(0xFF1877F2), Color.Transparent)), CircleShape)
            ) {
                Image(
                    painter = painterResource(id = com.example.R.drawable.fb_lite_logo),
                    contentDescription = "Facebook Logo",
                    modifier = Modifier
                        .size(104.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Dval Facebook Lite",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.5.sp
                ),
                textAlign = TextAlign.Center
            )

            Text(
                text = "Lightweight. Responsive. Fast.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color(0xFF94A3B8),
                    letterSpacing = 1.sp
                ),
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.weight(0.8f))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 40.dp)
            ) {
                Text(
                    text = "from",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color(0xFF64748B),
                        letterSpacing = 2.sp
                    )
                )
                
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(w * 0.25f, h * 0.5f)
                                cubicTo(w * 0.1f, h * 0.2f, w * 0.1f, h * 0.8f, w * 0.25f, h * 0.5f)
                                cubicTo(w * 0.4f, h * 0.2f, w * 0.6f, h * 0.8f, w * 0.75f, h * 0.5f)
                                cubicTo(w * 0.9f, h * 0.2f, w * 0.9f, h * 0.8f, w * 0.75f, h * 0.5f)
                                cubicTo(w * 0.6f, h * 0.2f, w * 0.4f, h * 0.8f, w * 0.25f, h * 0.5f)
                            }
                            drawPath(
                                path = path,
                                color = Color(0xFF1877F2),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = "Meta",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 1.sp
                        )
                    )
                }
            }
        }
    }
}

/**
 * Beautiful Network Error/Offline recovery screen
 */
@Composable
fun NetworkErrorView(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = "Offline Warning",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Connection Failed",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "We couldn't connect to Facebook. Please check your internet connection and try again.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    ),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retry Icon"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Retry Loading",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}
