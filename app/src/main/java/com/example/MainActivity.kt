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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter

class MainActivity : ComponentActivity() {

    // WebView and Upload Callbacks
    private var webViewInstance: WebView? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoPath: String? = null

    // Notification and update callbacks
    private val webViewDownloadsMap = mutableMapOf<Long, String>()
    private var updateDownloadId: Long = -1L
    private var updateInfoPending: UpdateInfo? = null
    private var globalStartUpdateDownload: ((UpdateInfo) -> Unit)? = null
    private var onUpdateDownloadFinished: ((UpdateInfo, File) -> Unit)? = null
    private var onUpdateDownloadFailed: ((String, UpdateInfo?) -> Unit)? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == action) {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId == -1L) return

                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = if (statusColumn != -1) cursor.getInt(statusColumn) else -1
                    
                    if (downloadId == updateDownloadId) {
                        val info = updateInfoPending
                        if (info != null) {
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                showNotification(
                                    notificationId = 10001,
                                    title = "Update Download Completed",
                                    text = "Successfully downloaded FB Lite v${info.versionName}",
                                    icon = android.R.drawable.stat_sys_download_done,
                                    pendingIntent = createInstallPendingIntent(context, info),
                                    soundAndVibrate = true
                                )
                                showNotification(
                                    notificationId = 10002,
                                    title = "Update Ready to Install",
                                    text = "Click to install version v${info.versionName} now.",
                                    icon = android.R.drawable.stat_sys_download_done,
                                    pendingIntent = createInstallPendingIntent(context, info),
                                    soundAndVibrate = true
                                )
                                val destFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "app-update.apk")
                                runOnUiThread {
                                    onUpdateDownloadFinished?.invoke(info, destFile)
                                }
                            } else if (status == DownloadManager.STATUS_FAILED) {
                                val reasonColumn = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                val reason = if (reasonColumn != -1) cursor.getInt(reasonColumn) else -1
                                showNotification(
                                    notificationId = 10001,
                                    title = "Update Download Failed",
                                    text = "Could not download update. Code: $reason",
                                    icon = android.R.drawable.stat_notify_error,
                                    soundAndVibrate = true
                                )
                                runOnUiThread {
                                    onUpdateDownloadFailed?.invoke("Download failed with code: $reason", info)
                                }
                            }
                        }
                    } else if (webViewDownloadsMap.containsKey(downloadId)) {
                        val filename = webViewDownloadsMap[downloadId] ?: "file"
                        webViewDownloadsMap.remove(downloadId)
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            showNotification(
                                notificationId = downloadId.toInt(),
                                title = "Download Completed",
                                text = "Successfully downloaded $filename",
                                icon = android.R.drawable.stat_sys_download_done,
                                pendingIntent = createOpenFilePendingIntent(context, downloadId),
                                soundAndVibrate = true
                            )
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            showNotification(
                                notificationId = downloadId.toInt(),
                                title = "Download Failed",
                                text = "Failed to download $filename",
                                icon = android.R.drawable.stat_notify_error,
                                soundAndVibrate = true
                            )
                        }
                    }
                }
                cursor?.close()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if ("com.example.INSTALL_UPDATE" == intent.action) {
            val filePath = intent.getStringExtra("filePath")
            if (filePath != null) {
                val file = File(filePath)
                if (file.exists()) {
                    triggerInstall(this, file)
                }
            }
        } else if ("com.example.START_UPDATE_DOWNLOAD" == intent.action) {
            val downloadUrl = intent.getStringExtra("downloadUrl")
            val versionName = intent.getStringExtra("versionName")
            val body = intent.getStringExtra("body")
            if (downloadUrl != null && versionName != null) {
                val info = UpdateInfo(
                    versionCode = 0,
                    versionName = versionName,
                    downloadUrl = downloadUrl,
                    updateMessage = body ?: "A new update is available."
                )
                runOnUiThread {
                    globalStartUpdateDownload?.invoke(info)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "downloads_and_updates"
            val channelName = "Downloads and Updates"
            val descriptionText = "Notifications for download status and in-app updates"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = descriptionText
                enableLights(true)
                lightColor = android.graphics.Color.parseColor("#800080")
                enableVibration(true)
                vibrationPattern = longArrayOf(100, 200, 300, 400)
                val defaultUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(defaultUri, audioAttributes)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(
        notificationId: Int,
        title: String,
        text: String,
        icon: Int = android.R.drawable.stat_sys_download_done,
        pendingIntent: PendingIntent? = null,
        soundAndVibrate: Boolean = true
    ) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = androidx.core.app.NotificationCompat.Builder(this, "downloads_and_updates")
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        if (soundAndVibrate) {
            builder.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))
            builder.setVibrate(longArrayOf(100, 200, 300, 400))
        }

        notificationManager.notify(notificationId, builder.build())
    }

    private fun createOpenFilePendingIntent(context: Context, downloadId: Long): PendingIntent? {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = downloadManager.getUriForDownloadedFile(downloadId)
            if (uri != null) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, downloadManager.getMimeTypeForDownloadedFile(downloadId) ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                return PendingIntent.getActivity(context, downloadId.toInt(), intent, flags)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun createInstallPendingIntent(context: Context, info: UpdateInfo): PendingIntent {
        val destFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "app-update.apk")
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.example.INSTALL_UPDATE"
            putExtra("versionName", info.versionName)
            putExtra("filePath", destFile.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(context, 10002, intent, flags)
    }

    private fun showUpdateNotification(context: Context, info: UpdateInfo) {
        val clickIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val clickFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val clickPendingIntent = PendingIntent.getActivity(context, 20001, clickIntent, clickFlags)

        val updateIntent = Intent(context, MainActivity::class.java).apply {
            action = "com.example.START_UPDATE_DOWNLOAD"
            putExtra("downloadUrl", info.downloadUrl)
            putExtra("versionName", info.versionName)
            putExtra("body", info.updateMessage)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val updatePendingIntent = PendingIntent.getActivity(context, 20002, updateIntent, clickFlags)

        val builder = androidx.core.app.NotificationCompat.Builder(context, "downloads_and_updates")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("New Update Available")
            .setContentText("FB Lite v${info.versionName} is now available.")
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText("Version v${info.versionName} is now available.\n\n${info.updateMessage}"))
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(clickPendingIntent)
            .addAction(android.R.drawable.stat_sys_download, "Update Now", updatePendingIntent)
            .setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(longArrayOf(100, 200, 300, 400))

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(10000, builder.build())
    }

    val currentVersionCode: Long by lazy {
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            1L
        }
    }

    val currentVersionName: String by lazy {
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    fun isNewerVersion(current: String, latest: String): Boolean {
        val cleanCurrent = current.trim().removePrefix("v").removePrefix("V").trim()
        val cleanLatest = latest.trim().removePrefix("v").removePrefix("V").trim()
        
        val currentParts = cleanCurrent.split(".")
        val latestParts = cleanLatest.split(".")
        
        val maxLength = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLength) {
            val currentPart = currentParts.getOrNull(i)?.toIntOrNull() ?: 0
            val latestPart = latestParts.getOrNull(i)?.toIntOrNull() ?: 0
            if (latestPart > currentPart) {
                return true
            } else if (currentPart > latestPart) {
                return false
            }
        }
        return false
    }

    private suspend fun performUpdateCheck(): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://api.github.com/repos/kennymichk1230-cloud/Dval-Facebook/releases/latest")
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Dval-Facebook-Updater")
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonText)
                    val tagName = json.getString("tag_name")
                    val body = json.optString("body", "A new update is available for FB Lite.")
                    
                    val assets = json.optJSONArray("assets")
                    var downloadUrl = ""
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name == "app-release.apk") {
                                downloadUrl = asset.getString("browser_download_url")
                                break
                            } else if (name.endsWith(".apk") && downloadUrl.isEmpty()) {
                                downloadUrl = asset.getString("browser_download_url")
                            }
                        }
                    }
                    
                    if (downloadUrl.isNotEmpty()) {
                        UpdateInfo(
                            versionCode = 0,
                            versionName = tagName,
                            downloadUrl = downloadUrl,
                            updateMessage = body
                        )
                    } else {
                        null
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } finally {
                connection?.disconnect()
            }
        }
    }

    private suspend fun performUpdateCheck(urlStr: String): UpdateInfo? {
        return performUpdateCheck()
    }

    private fun triggerInstall(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(context, "Please enable 'Install unknown apps' permission to update.", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to launch installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

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

        // Create the notification channel
        createNotificationChannel()

        // Register the download broadcast receiver
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(downloadReceiver, filter)
        }

        // Process any launch intent
        handleIntent(intent)

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
                var urlInputText by remember { mutableStateOf("https://m.facebook.com") }

                // --- Self-Update System States ---
                var updateState by remember { mutableStateOf<UpdateSystemState>(UpdateSystemState.Idle) }
                var updateProgress by remember { mutableStateOf(0f) }
                var downloadedBytesStr by remember { mutableStateOf("") }
                var updateJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                val coroutineScope = rememberCoroutineScope()
                val context = LocalContext.current

                // Check for updates on startup and once every 24 hours
                LaunchedEffect(Unit) {
                    delay(3500) // Let splash screen load cleanly
                    coroutineScope.launch {
                        val sharedPrefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                        
                        suspend fun runCheck() {
                            val info = performUpdateCheck()
                            if (info != null && isNewerVersion(currentVersionName, info.versionName)) {
                                updateState = UpdateSystemState.UpdateAvailable(info)
                                showUpdateNotification(context, info)
                            }
                            sharedPrefs.edit().putLong("last_update_check_time", System.currentTimeMillis()).apply()
                        }

                        // Always check on startup
                        runCheck()

                        // Check periodically every 24 hours
                        while (isActive) {
                            delay(60 * 60 * 1000) // check hourly if the 24 hours has passed
                            val lastCheck = sharedPrefs.getLong("last_update_check_time", 0L)
                            val now = System.currentTimeMillis()
                            if (now - lastCheck >= 24 * 60 * 60 * 1000) {
                                runCheck()
                            }
                        }
                    }
                }

                fun startUpdateDownload(info: UpdateInfo) {
                    updateState = UpdateSystemState.Downloading(info)
                    updateProgress = 0f
                    downloadedBytesStr = "Starting download..."
                    
                    updateJob = coroutineScope.launch {
                        try {
                            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                            val destFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "app-update.apk")
                            if (destFile.exists()) {
                                destFile.delete()
                            }
                            
                            val request = DownloadManager.Request(Uri.parse(info.downloadUrl)).apply {
                                setTitle("FB Lite Update v${info.versionName}")
                                setDescription("Downloading new update from GitHub Releases")
                                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "app-update.apk")
                            }
                            
                            val downloadId = downloadManager.enqueue(request)
                            this@MainActivity.updateDownloadId = downloadId
                            this@MainActivity.updateInfoPending = info
                            
                            showNotification(
                                notificationId = 10001,
                                title = "Update Download Started",
                                text = "Downloading FB Lite v${info.versionName}...",
                                icon = android.R.drawable.stat_sys_download,
                                soundAndVibrate = true
                            )
                            
                            var downloading = true
                            while (downloading && isActive) {
                                delay(500)
                                val query = DownloadManager.Query().setFilterById(downloadId)
                                val cursor = downloadManager.query(query)
                                if (cursor != null && cursor.moveToFirst()) {
                                    val bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                                    val bytesTotal = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                                    
                                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                        downloading = false
                                        updateProgress = 1f
                                        updateState = UpdateSystemState.ReadyToInstall(info, destFile)
                                    } else if (status == DownloadManager.STATUS_FAILED) {
                                        downloading = false
                                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                                        throw IOException("Download failed via DownloadManager. Reason code: $reason")
                                    } else if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING) {
                                        if (bytesTotal > 0) {
                                            updateProgress = bytesDownloaded.toFloat() / bytesTotal
                                            val currentMb = bytesDownloaded.toFloat() / (1024 * 1024)
                                            val totalMb = bytesTotal.toFloat() / (1024 * 1024)
                                            val percent = updateProgress * 100
                                            downloadedBytesStr = String.format(Locale.getDefault(), "%.2f MB / %.2f MB (%.0f%%)", currentMb, totalMb, percent)
                                        } else {
                                            updateProgress = -1f
                                            val currentMb = bytesDownloaded.toFloat() / (1024 * 1024)
                                            downloadedBytesStr = String.format(Locale.getDefault(), "%.2f MB downloaded", currentMb)
                                        }
                                    }
                                }
                                cursor?.close()
                            }
                        } catch (e: Exception) {
                            if (e is java.io.InterruptedIOException || e.message?.contains("cancelled") == true) {
                                updateState = UpdateSystemState.Idle
                            } else {
                                updateState = UpdateSystemState.Error("Failed to download update: ${e.message}", info)
                            }
                        }
                    }
                }

                // Bind activity callbacks to Compose states
                SideEffect {
                    globalStartUpdateDownload = { info ->
                        startUpdateDownload(info)
                    }
                    onUpdateDownloadFinished = { info, localFile ->
                        updateProgress = 1f
                        updateState = UpdateSystemState.ReadyToInstall(info, localFile)
                    }
                    onUpdateDownloadFailed = { errorStr, info ->
                        updateState = UpdateSystemState.Error(errorStr, info)
                    }
                }

                LaunchedEffect(currentUrl) {
                    urlInputText = currentUrl
                }

                fun navigateToUrl(input: String) {
                    var targetUrl = input.trim()
                    if (targetUrl.isEmpty()) return
                    
                    // If it's a sub-page of facebook (e.g. "messages", "/messages", "notifications")
                    if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://") && !targetUrl.contains(".")) {
                        val cleanPath = if (targetUrl.startsWith("/")) targetUrl.substring(1) else targetUrl
                        targetUrl = if (isDesktopSite) {
                            "https://www.facebook.com/$cleanPath"
                        } else {
                            "https://m.facebook.com/$cleanPath"
                        }
                    } else if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                        targetUrl = "https://$targetUrl"
                    }
                    
                    webViewInstance?.loadUrl(targetUrl)
                }

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
                                    tint = Color(0xFFD0BCFF),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                
                                BasicTextField(
                                    value = urlInputText,
                                    onValueChange = { urlInputText = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("url_input_field"),
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Go
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onGo = {
                                            navigateToUrl(urlInputText)
                                        }
                                    ),
                                    cursorBrush = SolidColor(Color(0xFFD0BCFF)),
                                    decorationBox = { innerTextField ->
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (urlInputText.isEmpty()) {
                                                Text(
                                                    text = "Enter URL or page...",
                                                    color = Color(0xFFCAC4D0).copy(alpha = 0.6f),
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                            innerTextField()
                                        }
                                    }
                                )

                                if (urlInputText.isNotEmpty()) {
                                    IconButton(
                                        onClick = { navigateToUrl(urlInputText) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowForward,
                                            contentDescription = "Navigate to URL",
                                            tint = Color(0xFFD0BCFF),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
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
                                    onGoForward = { webViewInstance?.goForward() },
                                    currentVersionName = currentVersionName,
                                    onCheckForUpdates = {
                                        showMenu = false
                                        coroutineScope.launch {
                                            Toast.makeText(this@MainActivity, "Checking for updates...", Toast.LENGTH_SHORT).show()
                                            val info = performUpdateCheck()
                                            if (info != null && isNewerVersion(currentVersionName, info.versionName)) {
                                                updateState = UpdateSystemState.UpdateAvailable(info)
                                            } else {
                                                Toast.makeText(this@MainActivity, "No updates available (Current version is up-to-date: v$currentVersionName)", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    onSimulateUpdate = {
                                        showMenu = false
                                        val mockInfo = UpdateInfo(
                                            versionCode = 999,
                                            versionName = "9.9",
                                            downloadUrl = "https://raw.githubusercontent.com/sheilaj08102/fb-lite-updater/main/dummy.apk",
                                            updateMessage = "This is a simulated update to demonstrate the polished Material 3 self-update flow.\n\nIt features live download progress tracking, adaptive layouts, and a secure FileProvider installer trigger!"
                                        )
                                        updateState = UpdateSystemState.UpdateAvailable(mockInfo)
                                    }
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

                    // Self-Update Dialog Overlay
                    UpdateDialogOverlay(
                        state = updateState,
                        progress = updateProgress,
                        downloadedStr = downloadedBytesStr,
                        onUpdateClick = {
                            val state = updateState
                            if (state is UpdateSystemState.UpdateAvailable) {
                                startUpdateDownload(state.info)
                            }
                        },
                        onLaterClick = {
                            updateJob?.cancel()
                            updateState = UpdateSystemState.Idle
                        },
                        onInstallClick = {
                            val state = updateState
                            if (state is UpdateSystemState.ReadyToInstall) {
                                triggerInstall(context, state.localFile)
                            }
                        },
                        onRetryClick = {
                            val state = updateState
                            if (state is UpdateSystemState.Error) {
                                state.info?.let { startUpdateDownload(it) } ?: run {
                                    updateState = UpdateSystemState.Idle
                                }
                            }
                        },
                        onDismissError = {
                            updateState = UpdateSystemState.Idle
                        }
                    )
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
                                val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
                                val request = DownloadManager.Request(Uri.parse(url)).apply {
                                    setMimeType(mimetype)
                                    val cookies = CookieManager.getInstance().getCookie(url)
                                    addRequestHeader("cookie", cookies)
                                    addRequestHeader("User-Agent", userAgent)
                                    setDescription("Downloading $filename...")
                                    setTitle(filename)
                                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    setDestinationInExternalPublicDir(
                                        Environment.DIRECTORY_DOWNLOADS,
                                        filename
                                    )
                                }

                                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                val id = dm.enqueue(request)
                                webViewDownloadsMap[id] = filename
                                
                                showNotification(
                                    notificationId = id.toInt(),
                                    title = "Download Started",
                                    text = "Downloading $filename...",
                                    icon = android.R.drawable.stat_sys_download,
                                    soundAndVibrate = true
                                )
                                Toast.makeText(ctx, "Starting download...", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                showNotification(
                                    notificationId = System.currentTimeMillis().toInt(),
                                    title = "Download Failed",
                                    text = "Could not start download: ${e.message}",
                                    icon = android.R.drawable.stat_notify_error,
                                    soundAndVibrate = true
                                )
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
    onGoForward: () -> Unit,
    currentVersionName: String,
    onCheckForUpdates: () -> Unit,
    onSimulateUpdate: () -> Unit
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
                            text = "Fast lightweight browser engine. Version v$currentVersionName",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFCAC4D0))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF49454F))
                Spacer(modifier = Modifier.height(8.dp))

                // Check for updates option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onCheckForUpdates)
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Check for updates icon",
                        tint = Color(0xFFD0BCFF),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Check for Updates",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFE6E1E5)
                            )
                        )
                        Text(
                            text = "Fetch online version manifest",
                            style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFCAC4D0))
                        )
                    }
                }

                // Simulate update option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onSimulateUpdate)
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Simulate update icon",
                        tint = Color(0xFFD0BCFF),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Simulate Self-Update",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFE6E1E5)
                            )
                        )
                        Text(
                            text = "Demo update UI and progress bar",
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

sealed class UpdateSystemState {
    object Idle : UpdateSystemState()
    data class UpdateAvailable(val info: UpdateInfo) : UpdateSystemState()
    data class Downloading(val info: UpdateInfo) : UpdateSystemState()
    data class ReadyToInstall(val info: UpdateInfo, val localFile: File) : UpdateSystemState()
    data class Error(val message: String, val info: UpdateInfo?) : UpdateSystemState()
}

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val updateMessage: String
)

@Composable
fun UpdateDialogOverlay(
    state: UpdateSystemState,
    progress: Float,
    downloadedStr: String,
    onUpdateClick: () -> Unit,
    onLaterClick: () -> Unit,
    onInstallClick: () -> Unit,
    onRetryClick: () -> Unit,
    onDismissError: () -> Unit
) {
    if (state is UpdateSystemState.Idle) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(enabled = false) {}, // prevent click-through
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF49454F)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (state) {
                            is UpdateSystemState.UpdateAvailable -> Icons.Default.Refresh
                            is UpdateSystemState.Downloading -> Icons.Default.Refresh
                            is UpdateSystemState.ReadyToInstall -> Icons.Default.Check
                            is UpdateSystemState.Error -> Icons.Default.Warning
                            else -> Icons.Default.Info
                        },
                        contentDescription = "Update Status Icon",
                        tint = if (state is UpdateSystemState.Error) Color(0xFFF2B8B5) else Color(0xFFD0BCFF),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title
                val titleText = when (state) {
                    is UpdateSystemState.UpdateAvailable -> "New Update Available"
                    is UpdateSystemState.Downloading -> "Downloading Update"
                    is UpdateSystemState.ReadyToInstall -> "Update Ready to Install"
                    is UpdateSystemState.Error -> "Update Failed"
                    else -> ""
                }

                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE6E1E5),
                        textAlign = TextAlign.Center
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Content Description
                when (state) {
                    is UpdateSystemState.UpdateAvailable -> {
                        val info = state.info
                        Text(
                            text = "Version v${info.versionName} is now available.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFD0BCFF)
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = info.updateMessage,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color(0xFFCAC4D0),
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                        )
                    }
                    is UpdateSystemState.Downloading -> {
                        Text(
                            text = "Please wait while the update is being downloaded.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color(0xFFCAC4D0),
                                textAlign = TextAlign.Center
                            )
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (progress >= 0f) {
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = Color(0xFFD0BCFF),
                                trackColor = Color(0xFF49454F)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = downloadedStr,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Color(0xFFD0BCFF),
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = Color(0xFFD0BCFF),
                                trackColor = Color(0xFF49454F)
                            )
                        }
                    }
                    is UpdateSystemState.ReadyToInstall -> {
                        val info = state.info
                        Text(
                            text = "The update to version v${info.versionName} has been downloaded successfully. Click Install below to apply the update.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color(0xFFCAC4D0),
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                        )
                    }
                    is UpdateSystemState.Error -> {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color(0xFFF2B8B5),
                                textAlign = TextAlign.Center
                            )
                        )
                    }
                    else -> {}
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Actions Button Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (state) {
                        is UpdateSystemState.UpdateAvailable -> {
                            // Later button
                            Button(
                                onClick = onLaterClick,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF49454F),
                                    contentColor = Color(0xFFE6E1E5)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Later")
                            }

                            // Update Now button
                            Button(
                                onClick = onUpdateClick,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFD0BCFF),
                                    contentColor = Color(0xFF21005D)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Update Now", fontWeight = FontWeight.Bold)
                            }
                        }
                        is UpdateSystemState.Downloading -> {
                            // Cancel button
                            Button(
                                onClick = onLaterClick, // behaves as cancel
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF49454F),
                                    contentColor = Color(0xFFE6E1E5)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Cancel")
                            }
                        }
                        is UpdateSystemState.ReadyToInstall -> {
                            // Later button
                            Button(
                                onClick = onLaterClick,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF49454F),
                                    contentColor = Color(0xFFE6E1E5)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Dismiss")
                            }

                            // Install button
                            Button(
                                onClick = onInstallClick,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFD0BCFF),
                                    contentColor = Color(0xFF21005D)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Install Now", fontWeight = FontWeight.Bold)
                            }
                        }
                        is UpdateSystemState.Error -> {
                            // Close button
                            Button(
                                onClick = onDismissError,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF49454F),
                                    contentColor = Color(0xFFE6E1E5)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Close")
                            }

                            // Retry button
                            Button(
                                onClick = onRetryClick,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFD0BCFF),
                                    contentColor = Color(0xFF21005D)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Retry", fontWeight = FontWeight.Bold)
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}
