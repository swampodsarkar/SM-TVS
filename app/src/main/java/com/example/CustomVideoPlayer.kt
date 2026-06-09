package com.example

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.SettingsManager
import com.example.ui.theme.Slate300
import com.example.ui.theme.Red500
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import android.content.ContextWrapper

fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun CustomLiveVideoPlayer(
    viewModel: IptvViewModel,
    settingsManager: SettingsManager,
    modifier: Modifier = Modifier
) {
    val selectedChannel by viewModel.selectedChannel.collectAsState()
    val allChannels by viewModel.channels.collectAsState()
    if (selectedChannel == null) return

    val context = LocalContext.current
    val activity = context.findActivity()

    var isFullscreen by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var isControllerVisible by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    val quality by settingsManager.quality.collectAsState(initial = "Auto")
    val isAutoPlay by settingsManager.isAutoPlay.collectAsState(initial = true)

    val exoPlayer = remember {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0")
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build().apply {
                val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                setAudioAttributes(audioAttributes, true)
                playWhenReady = isAutoPlay
            }
    }
    
    // Update playWhenReady when autoPlay changes
    LaunchedEffect(isAutoPlay) {
        exoPlayer.playWhenReady = isAutoPlay
    }
    
    // Apply quality changes
    LaunchedEffect(quality) {
        if (quality == "High") {
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .setForceHighestSupportedBitrate(true)
                .build()
        } else if (quality == "Low") {
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .setForceLowestBitrate(true)
                .build()
        } else {
            // Auto
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .setForceHighestSupportedBitrate(false)
                .setForceLowestBitrate(false)
                .build()
        }
    }

    DisposableEffect(selectedChannel) {
        selectedChannel?.let {
            exoPlayer.setMediaItem(MediaItem.fromUri(it.streamUrl))
            exoPlayer.prepare()
            exoPlayer.play()
        }
        onDispose {}
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                super.onPlayerError(error)
                // Auto reconnect
                exoPlayer.prepare()
                exoPlayer.play()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }
    
    var sleepTimerValue by remember { mutableStateOf(0) }
    
    LaunchedEffect(sleepTimerValue) {
        if (sleepTimerValue > 0) {
            delay(sleepTimerValue * 60 * 1000L)
            exoPlayer.pause()
            activity?.finish() // Or just pause
            sleepTimerValue = 0
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                // Keep playing in background (Audio)
            } else if (event == Lifecycle.Event.ON_RESUME) {
                // Ensure orientation is reset if not fullscreen
                if (!isFullscreen) {
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val playerContent = @Composable {
        Box(modifier = if (isFullscreen) Modifier
            .fillMaxSize()
            .background(Color.Black) else modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setShowSubtitleButton(true)
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                            isControllerVisible = visibility == android.view.View.VISIBLE
                        })
                    }
                },
                update = { view ->
                    view.resizeMode = resizeMode
                }
            )
            
            // Overlay controls
            AnimatedVisibility(
                visible = isControllerVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = {
                        isMuted = !isMuted
                        exoPlayer.volume = if (isMuted) 0f else 1f
                    }, modifier = Modifier.background(Color.Black.copy(alpha=0.5f), CircleShape)) {
                        val icon = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp
                        Icon(icon, "Mute", tint = Color.White)
                    }

                    IconButton(onClick = { showQualityDialog = true }, modifier = Modifier.background(Color.Black.copy(alpha=0.5f), CircleShape)) {
                        Icon(Icons.Default.Settings, "Settings", tint = Color.White)
                    }
                    
                    IconButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                            activity?.enterPictureInPictureMode(params)
                        }
                    }, modifier = Modifier.background(Color.Black.copy(alpha=0.5f), CircleShape)) {
                        Icon(Icons.Default.PictureInPicture, "PiP", tint = Color.White)
                    }
                    
                    IconButton(onClick = { isFullscreen = !isFullscreen }, modifier = Modifier.background(Color.Black.copy(alpha=0.5f), CircleShape)) {
                        Icon(if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Fullscreen", tint = Color.White)
                    }
                }
            }
            
            AnimatedVisibility(
                visible = isControllerVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
            ) {
                IconButton(onClick = {
                    val idx = allChannels.indexOf(selectedChannel).takeIf { it != -1 } ?: return@IconButton
                    val prev = if (idx > 0) allChannels[idx - 1] else allChannels.last()
                    viewModel.selectChannel(prev)
                }, modifier = Modifier.background(Color.Black.copy(alpha=0.5f), CircleShape)) {
                    Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White)
                }
            }

            AnimatedVisibility(
                visible = isControllerVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)
            ) {
                IconButton(onClick = {
                    val idx = allChannels.indexOf(selectedChannel).takeIf { it != -1 } ?: return@IconButton
                    val next = if (idx < allChannels.size - 1) allChannels[idx + 1] else allChannels.first()
                    viewModel.selectChannel(next)
                }, modifier = Modifier.background(Color.Black.copy(alpha=0.5f), CircleShape)) {
                    Icon(Icons.Default.SkipNext, "Next", tint = Color.White)
                }
            }
        }
    }

    // Ensure orientation is applied
    LaunchedEffect(isFullscreen) {
        if (isFullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    if (isFullscreen) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { isFullscreen = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            playerContent()
        }
    } else {
        playerContent()
    }

    if (showQualityDialog) {
        var isRotationLocked by remember { mutableStateOf(false) }
        
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text("Quality & Settings") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Quality (Auto Adapts)", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch { settingsManager.setQuality("High") }
                        }, modifier = Modifier.weight(1f)) { Text("High") }
                        Button(onClick = {
                            scope.launch { settingsManager.setQuality("Low") }
                        }, modifier = Modifier.weight(1f)) { Text("Data Saver") }
                    }
                    
                    Text("Aspect Ratio", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT }, modifier = Modifier.weight(1f)) { Text("Fit") }
                        Button(onClick = { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL }, modifier = Modifier.weight(1f)) { Text("Stretch") }
                        Button(onClick = { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM }, modifier = Modifier.weight(1f)) { Text("Zoom") }
                    }

                    Text("Speed", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { playbackSpeed = 0.5f; exoPlayer.setPlaybackSpeed(0.5f) }, modifier = Modifier.weight(1f)) { Text("0.5x") }
                        Button(onClick = { playbackSpeed = 1.0f; exoPlayer.setPlaybackSpeed(1.0f) }, modifier = Modifier.weight(1f)) { Text("1x") }
                        Button(onClick = { playbackSpeed = 1.5f; exoPlayer.setPlaybackSpeed(1.5f) }, modifier = Modifier.weight(1f)) { Text("1.5x") }
                    }
                    
                    Text("More Options", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val url = selectedChannel?.streamUrl ?: return@Button
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Channel"))
                        }, modifier = Modifier.weight(1f)) { Text("Share") }
                        
                        Button(onClick = {
                            exoPlayer.prepare()
                            exoPlayer.play()
                        }, modifier = Modifier.weight(1f)) { Text("Reload") }
                    }
                    
                    Text("Sleep Timer", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { sleepTimerValue = 15; showQualityDialog = false }, modifier = Modifier.weight(1f)) { Text("15m") }
                        Button(onClick = { sleepTimerValue = 30; showQualityDialog = false }, modifier = Modifier.weight(1f)) { Text("30m") }
                        Button(onClick = { sleepTimerValue = 60; showQualityDialog = false }, modifier = Modifier.weight(1f)) { Text("60m") }
                    }
                    if (sleepTimerValue > 0) {
                        Text("Timer Set: $sleepTimerValue min", color = MaterialTheme.colorScheme.primary)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Screen Rotation Lock")
                        Switch(checked = isRotationLocked, onCheckedChange = { isRotationLocked = it })
                    }
                    if (isRotationLocked) {
                        LaunchedEffect(Unit) {
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                        }
                    } else {
                        LaunchedEffect(Unit) {
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQualityDialog = false }) { Text("Close") }
            }
        )
    }
}
