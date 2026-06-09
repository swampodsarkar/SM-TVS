package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout

import android.app.PictureInPictureParams
import android.util.Rational
import android.os.Build

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.example.firebase.FirebaseManager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.db.AppDatabase
import com.example.db.FavoriteChannel
import com.example.db.WatchHistory
import com.example.data.SettingsManager
import androidx.compose.ui.window.Dialog

data class LiveEvent(
    var id: String = "",
    var type: String = "other",
    var team1: String = "",
    var team1Flag: String = "",
    var team1FlagUrl: String? = null,
    var team2: String = "",
    var team2Flag: String = "",
    var team2FlagUrl: String? = null,
    var score1: String = "",
    var score2: String = "",
    var status: String = "upcoming",
    var statusText: String = "",
    var tournament: String = "",
    var channelId: String? = null,
    var startTime: Long? = null
)

data class Channel(
    val title: String,
    val group: String,
    val logoUrl: String,
    val streamUrl: String
)

class IptvViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val channelDao = db.channelDao()

    val favoriteChannels = channelDao.getAllFavorites()
    val watchHistory = channelDao.getWatchHistory()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels

    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = _categories

    private val _selectedCategory = MutableStateFlow("Trending")
    val selectedCategory: StateFlow<String> = _selectedCategory

    private val _selectedChannel = MutableStateFlow<Channel?>(null)
    val selectedChannel: StateFlow<Channel?> = _selectedChannel

    private val _liveEvents = MutableStateFlow<List<LiveEvent>>(emptyList())
    val liveEvents: StateFlow<List<LiveEvent>> = _liveEvents

    val filteredChannels: Flow<List<Channel>> = combine(_channels, _selectedCategory, _searchQuery) { all, cat, query ->
        var list = if (cat == "Trending") all else all.filter { it.group == cat }
        if (query.isNotEmpty()) {
            list = list.filter { it.title.contains(query, ignoreCase = true) }
        }
        list
    }

    init {
        fetchPlaylist()
        listenEvents()
    }

    fun toggleFavorite(channel: Channel, isFav: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val fav = FavoriteChannel(channel.streamUrl, channel.title, channel.group, channel.logoUrl, channel.streamUrl)
            if (isFav) {
                channelDao.deleteFavorite(fav)
            } else {
                channelDao.insertFavorite(fav)
            }
        }
    }

    private fun listenEvents() {
        try {
            val database = FirebaseManager.getDatabase()
            database.getReference("events").addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val events = mutableListOf<LiveEvent>()
                    for (child in snapshot.children) {
                        try {
                            val evt = child.getValue(LiveEvent::class.java)
                            if (evt != null) {
                                evt.id = child.key ?: ""
                                events.add(evt)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    _liveEvents.value = events
                }

                override fun onCancelled(error: DatabaseError) {
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun fetchPlaylist() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder().followRedirects(true).build()
                val request = Request.Builder()
                    .url("https://da.gd/VaAUn")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val response = client.newCall(request).execute()
                val content = response.body?.string() ?: ""

                val parsedChannels = parseM3U(content).filter { it.group != "Promo" }
                android.util.Log.d("IPTV", "Total channels parsed: ${parsedChannels.size}")
                _channels.value = parsedChannels
                
                val cats = parsedChannels.map { it.group }.filter { it.isNotEmpty() }.distinct().toMutableList()
                if (cats.remove("Sports")) {
                    cats.add(0, "Sports")
                }
                _categories.value = listOf("Trending") + cats
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun parseM3U(content: String): List<Channel> {
        val lines = content.lines()
        val channels = mutableListOf<Channel>()
        var currentTitle = ""
        var currentGroup = ""
        var currentLogo = ""
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:")) {
                val logoMatch = Regex("tvg-logo=\"([^\"]*)\"").find(trimmed)
                currentLogo = logoMatch?.groupValues?.get(1) ?: ""
                
                val groupMatch = Regex("group-title=\"([^\"]*)\"").find(trimmed)
                currentGroup = groupMatch?.groupValues?.get(1) ?: ""
                
                currentTitle = trimmed.substringAfterLast(",").trim()
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                if (currentTitle.isNotEmpty() || trimmed.startsWith("http")) { // Ensure we handle cases without EXTINF or empty titles
                    val title = if (currentTitle.isEmpty()) "Unknown Channel" else currentTitle
                    val group = if (currentGroup.isEmpty()) "Uncategorized" else currentGroup
                    channels.add(Channel(title = title, group = group, logoUrl = currentLogo, streamUrl = trimmed))
                    currentTitle = "" // Reset after grabbing to prevent applying same details to next unrelated URL
                }
            }
        }
        return channels
    }

    fun selectCategory(cat: String) {
        _selectedCategory.value = cat
    }

    fun selectChannel(channel: Channel) {
        _selectedChannel.value = channel
        viewModelScope.launch(Dispatchers.IO) {
            channelDao.insertWatchHistory(
                WatchHistory(
                    id = channel.streamUrl,
                    title = channel.title,
                    group = channel.group,
                    logoUrl = channel.logoUrl,
                    streamUrl = channel.streamUrl,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    com.example.firebase.FirebaseManager.init(this)
    enableEdgeToEdge()
    setContent {
      var showSplash by remember { mutableStateOf(true) }
      LaunchedEffect(Unit) {
        delay(2000)
        showSplash = false
      }

      val settingsManager = remember { SettingsManager(this) }
      val isDarkMode by settingsManager.isDarkMode.collectAsState(initial = true)
      
      MyApplicationTheme(darkTheme = isDarkMode, dynamicColor = false) {
        if (showSplash) {
          SplashScreen()
        } else {
            val viewModel: IptvViewModel = viewModel()
            val scrollState = rememberScrollState()
            var currentTab by remember { mutableStateOf("Home") }
            
            Scaffold(
              modifier = Modifier.fillMaxSize(),
              containerColor = MaterialTheme.colorScheme.background,
              bottomBar = { BottomNavBar(currentTab) { currentTab = it } }
            ) { innerPadding ->
              Column(
                modifier = Modifier
                  .padding(innerPadding)
                  .fillMaxSize()
                  .verticalScroll(scrollState)
                  .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
              ) {
                HeaderSection(viewModel)
                when (currentTab) {
                    "Home" -> {
                        CategoryPills(viewModel)
                        RecommendedChannels(viewModel) {
                            viewModel.selectChannel(it)
                            currentTab = "Live TV"
                        }
                    }
                    "Live TV" -> {
                        CustomLiveVideoPlayer(viewModel, settingsManager, modifier = Modifier.clip(RoundedCornerShape(24.dp)).border(1.dp, BorderDark, RoundedCornerShape(24.dp)))
                        RecommendedChannels(viewModel) {
                            viewModel.selectChannel(it)
                        }
                    }
                    "History" -> {
                        WatchHistoryScreen(viewModel) {
                            viewModel.selectChannel(it)
                            currentTab = "Live TV"
                        }
                    }
                    "Profile" -> {
                        ProfileScreen(viewModel)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
              }
            }
        }
      }
    }
  }
}

@Composable
fun SplashScreen() {
  Box(
    modifier = Modifier.fillMaxSize().background(Color.Black),
    contentAlignment = Alignment.Center
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text("SM TV", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Color.White)
      Text("Made By SRS", fontSize = 16.sp, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(top = 8.dp))
    }
  }
}

@Composable
fun HeaderSection(viewModel: IptvViewModel) {
  var tapCount by remember { mutableStateOf(0) }
  var showAdmin by remember { mutableStateOf(false) }
  var isSearching by remember { mutableStateOf(false) }
  val searchQuery by viewModel.searchQuery.collectAsState()

  if (showAdmin) {
      AdminDialog(onDismiss = { showAdmin = false })
  }

  if (isSearching) {
      OutlinedTextField(
          value = searchQuery,
          onValueChange = { viewModel.updateSearchQuery(it) },
          modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
          placeholder = { Text("Search channels...") },
          trailingIcon = {
              IconButton(onClick = {
                  isSearching = false
                  viewModel.updateSearchQuery("")
              }) {
                  Icon(Icons.Default.Close, contentDescription = "Close search")
              }
          },
          singleLine = true,
          shape = RoundedCornerShape(16.dp),
          colors = TextFieldDefaults.colors(
              focusedContainerColor = SurfaceDark,
              unfocusedContainerColor = SurfaceDark,
              focusedIndicatorColor = Color.Transparent,
              unfocusedIndicatorColor = Color.Transparent
          )
      )
  } else {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 24.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Box(
            modifier = Modifier
              .size(40.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(Brush.linearGradient(listOf(Cyan500, Blue600)))
              .clickable {
                  tapCount++
                  if (tapCount >= 7) {
                      tapCount = 0
                      showAdmin = true
                  }
              },
            contentAlignment = Alignment.Center
          ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Logo", tint = Color.White, modifier = Modifier.size(24.dp))
          }
          Column {
            Text("SM TV", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Slate100)
            Text("Premium Live", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Cyan400, letterSpacing = 1.sp)
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          IconButton(
            onClick = { isSearching = true },
            modifier = Modifier
              .size(44.dp)
              .clip(CircleShape)
              .background(SurfaceDark)
          ) {
            Icon(Icons.Default.Search, contentDescription = "Search", tint = Slate300)
          }
          IconButton(
            onClick = {},
            modifier = Modifier
              .size(44.dp)
              .clip(CircleShape)
              .background(SurfaceDark)
          ) {
            Icon(Icons.Default.Mic, contentDescription = "Voice", tint = Slate300)
          }
        }
      }
  }
}

@Composable
fun AdminDialog(onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var isAuthenticated by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SurfaceDark,
            modifier = Modifier.padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Admin Panel", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                if (!isAuthenticated) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        if (password == "admin123") {
                            isAuthenticated = true
                        }
                    }) {
                        Text("Login")
                    }
                } else {
                    Text("Firebase Controls", color = Color.White)
                    Button(onClick = {
                        val db = com.example.firebase.FirebaseManager.getDatabase()
                        val newEventId = db.getReference("events").push().key ?: return@Button
                        val evt = LiveEvent(
                            id = newEventId,
                            team1 = "Argentina",
                            team2 = "Brazil",
                            score1 = "1",
                            score2 = "0",
                            status = "live",
                            statusText = "Second Half",
                            tournament = "World Cup"
                        )
                        db.getReference("events").child(newEventId).setValue(evt)
                    }) {
                        Text("Add Event")
                    }
                }
            }
        }
    }
}

@Composable
fun LiveVideoPreview(viewModel: IptvViewModel) {
  val selectedChannel by viewModel.selectedChannel.collectAsState()
  if (selectedChannel == null) return

  val context = LocalContext.current
  val activity = context as? ComponentActivity
  var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FILL) }

  val exoPlayer = remember {
      val dataSourceFactory = DefaultHttpDataSource.Factory()
          .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
          .setAllowCrossProtocolRedirects(true)
          
      ExoPlayer.Builder(context)
          .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
          .build().apply {
          val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
              .setUsage(androidx.media3.common.C.USAGE_MEDIA)
              .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
              .build()
          setAudioAttributes(audioAttributes, true)
          playWhenReady = true
      }
  }

  DisposableEffect(selectedChannel) {
      selectedChannel?.let {
          val mediaItem = MediaItem.fromUri(it.streamUrl)
          exoPlayer.setMediaItem(mediaItem)
          exoPlayer.prepare()
          exoPlayer.play()
      }
      onDispose { }
  }

  DisposableEffect(Unit) {
      onDispose { exoPlayer.release() }
  }

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .aspectRatio(16f / 9f)
      .clip(RoundedCornerShape(24.dp))
      .background(Color.Black)
      .border(1.dp, BorderDark, RoundedCornerShape(24.dp))
  ) {
    if (selectedChannel != null) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            update = { playerView ->
                playerView.resizeMode = resizeMode
            }
        )
    }

    // Top Tags
    Row(
      modifier = Modifier
        .align(Alignment.TopStart)
        .padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        modifier = Modifier
          .clip(RoundedCornerShape(4.dp))
          .background(Red500)
          .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color.White))
        Text("Live", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
      }
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(4.dp))
          .background(Color.Black.copy(alpha = 0.4f))
          .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
          .padding(horizontal = 8.dp, vertical = 2.dp)
      ) {
        Text("1080p • 60fps", color = Color.White, fontSize = 10.sp)
      }
    }
    
    // Bottom Title
    Row(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .fillMaxWidth()
        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
        .padding(16.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.Bottom
    ) {
      Column {
        Text(selectedChannel?.group ?: "", color = Slate300, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(selectedChannel?.title ?: "Select a channel to play", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
          modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .clickable {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                    activity?.enterPictureInPictureMode(params)
                }
            },
          contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PictureInPicture, contentDescription = "PiP", tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Box(
          modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .clickable {
                resizeMode = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                             else if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) AspectRatioFrameLayout.RESIZE_MODE_FILL
                             else AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
          contentAlignment = Alignment.Center
        ) {
          Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen", tint = Color.White, modifier = Modifier.size(20.dp))
        }
      }
    }
  }
}

@Composable
fun SportsDashboard(viewModel: IptvViewModel) {
  val events by viewModel.liveEvents.collectAsState()
  if (events.isEmpty()) return

  val currentEvent = events.firstOrNull { it.status == "live" } ?: events.first()

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(24.dp))
      .background(SurfaceDark)
      .border(1.dp, BorderDark, RoundedCornerShape(24.dp))
      .padding(16.dp)
  ) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text(currentEvent.tournament.ifEmpty { "LIVE SCOREBOARD" }, color = Slate400, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
      Text("View All Events →", color = Cyan400, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
    
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      // Team 1
      Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
        if (currentEvent.team1FlagUrl?.isNotEmpty() == true) {
            AsyncImage(model = currentEvent.team1FlagUrl, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Color.White))
        } else {
            Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.05f)), contentAlignment = Alignment.Center) { Text(currentEvent.team1Flag.ifEmpty { "🏠" }, fontSize = 24.sp) }
        }
        Text(currentEvent.team1.ifEmpty { "Team 1" }, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Slate100, maxLines = 1)
      }
      // Score
      Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1.5f)) {
        if (currentEvent.score1.isNotEmpty() || currentEvent.score2.isNotEmpty()) {
            Text("${currentEvent.score1} - ${currentEvent.score2}", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Slate100)
        } else {
            Text("VS", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Slate100)
        }
        if (currentEvent.statusText.isNotEmpty()) {
            Text(currentEvent.statusText, fontSize = 10.sp, color = Slate500, modifier = Modifier.padding(top = 4.dp))
        }
        val statusColor = if(currentEvent.status == "live") Red500 else if (currentEvent.status == "upcoming") Cyan500 else Slate500
        Box(modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).background(statusColor.copy(alpha = 0.1f)).border(1.dp, statusColor.copy(alpha = 0.2f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) {
          Text(currentEvent.status.uppercase(), color = statusColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
      }
      // Team 2
      Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
        if (currentEvent.team2FlagUrl?.isNotEmpty() == true) {
            AsyncImage(model = currentEvent.team2FlagUrl, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Color.White))
        } else {
            Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.05f)), contentAlignment = Alignment.Center) { Text(currentEvent.team2Flag.ifEmpty { "✈️" }, fontSize = 24.sp) }
        }
        Text(currentEvent.team2.ifEmpty { "Team 2" }, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Slate100, maxLines = 1)
      }
    }
  }
}

@Composable
fun CategoryPills(viewModel: IptvViewModel) {
  val categories by viewModel.categories.collectAsState()
  val selectedCat by viewModel.selectedCategory.collectAsState()

  LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    items(categories.size) { index ->
      val cat = categories[index]
      CategoryPill(
          text = cat, 
          selected = cat == selectedCat,
          onClick = { viewModel.selectCategory(cat) }
      )
    }
  }
}

@Composable
fun CategoryPill(text: String, selected: Boolean, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(16.dp))
      .background(if (selected) Cyan600 else SurfaceDark)
      .border(1.dp, if (selected) Color.Transparent else BorderDark, RoundedCornerShape(16.dp))
      .clickable { onClick() }
      .padding(horizontal = 20.dp, vertical = 10.dp)
  ) {
    Text(text, color = if (selected) Color.White else Slate300, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
  }
}

@Composable
fun RecommendedChannels(viewModel: IptvViewModel, onChannelClick: (Channel) -> Unit) {
  val selectedCat by viewModel.selectedCategory.collectAsState()
  val favorites by viewModel.favoriteChannels.collectAsState(initial = emptyList())
  val filteredChannels by viewModel.filteredChannels.collectAsState(initial = emptyList())

  val displayChannels = filteredChannels.take(20)

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Channels (${displayChannels.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Slate100, modifier = Modifier.padding(horizontal = 4.dp))
    
    val rows = displayChannels.chunked(2)
    for (rowChannels in rows) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (channel in rowChannels) {
                val isFav = favorites.any { it.id == channel.streamUrl }
                ChannelCard(
                    modifier = Modifier.weight(1f).clickable { onChannelClick(channel) },
                    channel = channel,
                    isFavorite = isFav,
                    onToggleFavorite = { viewModel.toggleFavorite(channel, isFav) }
                )
            }
            if (rowChannels.size == 1) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
  }
}

@Composable
fun ChannelCard(modifier: Modifier = Modifier, channel: Channel, isFavorite: Boolean, onToggleFavorite: () -> Unit) {
  Row(
    modifier = modifier
      .clip(RoundedCornerShape(16.dp))
      .background(SurfaceDark)
      .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
      .padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    if (channel.logoUrl.isNotEmpty()) {
        AsyncImage(
            model = channel.logoUrl,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
        )
    } else {
        Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(SelectionDark), contentAlignment = Alignment.Center) {
            Text(channel.title.take(2).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Slate100)
        }
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(channel.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Slate100, maxLines = 1)
      Text(channel.group, fontSize = 10.sp, color = Slate500, maxLines = 1)
    }
    IconButton(onClick = onToggleFavorite, modifier = Modifier.size(24.dp)) {
        Icon(
            if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = "Favorite",
            tint = if (isFavorite) Red500 else Slate400,
            modifier = Modifier.size(20.dp)
        )
    }
  }
}

@Composable
fun WatchHistoryScreen(viewModel: IptvViewModel, onChannelClick: (Channel) -> Unit) {
    val history by viewModel.watchHistory.collectAsState(initial = emptyList())
    val favorites by viewModel.favoriteChannels.collectAsState(initial = emptyList())
    
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Recently Watched", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Slate100, modifier = Modifier.padding(horizontal = 4.dp))
        
        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                Text("Your watch history is empty", color = Slate500)
            }
        } else {
            for (item in history) {
                val isFav = favorites.any { it.id == item.streamUrl }
                val channel = Channel(item.title, item.group, item.logoUrl, item.streamUrl)
                ChannelCard(
                    modifier = Modifier.fillMaxWidth().clickable { onChannelClick(channel) },
                    channel = channel,
                    isFavorite = isFav,
                    onToggleFavorite = { viewModel.toggleFavorite(channel, isFav) }
                )
            }
        }
    }
}

@Composable
fun ProfileScreen(viewModel: IptvViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }

    if (showSettings) {
        SettingsDialog(settingsManager, onDismiss = { showSettings = false })
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Cyan500, Blue600))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Person, contentDescription = "Profile", tint = Color.White, modifier = Modifier.size(50.dp))
        }
        
        Text("Guest User", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Slate100)
        Text("ID: 15928374", fontSize = 14.sp, color = Slate500)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        val buttonColors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = Slate100)
        
        Button(onClick = { }, modifier = Modifier.fillMaxWidth(), colors = buttonColors, shape = RoundedCornerShape(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Favorite, contentDescription = null, tint = Red500)
                Spacer(modifier = Modifier.width(16.dp))
                Text("Favorite Channels", fontSize = 16.sp)
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        
        Button(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth(), colors = buttonColors, shape = RoundedCornerShape(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = Slate300)
                Spacer(modifier = Modifier.width(16.dp))
                Text("Settings", fontSize = 16.sp)
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SettingsDialog(settingsManager: SettingsManager, onDismiss: () -> Unit) {
    val isDarkMode by settingsManager.isDarkMode.collectAsState(initial = true)
    val quality by settingsManager.quality.collectAsState(initial = "Auto")
    val isAutoPlay by settingsManager.isAutoPlay.collectAsState(initial = true)
    
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SurfaceDark,
            modifier = Modifier.padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Dark Mode", color = Color.White)
                    Switch(checked = isDarkMode, onCheckedChange = { scope.launch { settingsManager.setDarkMode(it) } })
                }
                
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Auto Play", color = Color.White)
                    Switch(checked = isAutoPlay, onCheckedChange = { scope.launch { settingsManager.setAutoPlay(it) } })
                }
                
                Text("Video Quality", color = Color.White, modifier = Modifier.padding(top = 16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Auto", "High", "Low").forEach { q ->
                        FilterChip(
                            selected = quality == q,
                            onClick = { scope.launch { settingsManager.setQuality(q) } },
                            label = { Text(q) }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun BottomNavBar(currentTab: String, onTabSelect: (String) -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(NavDark)
      .border(1.dp, BorderDark)
      .padding(horizontal = 16.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.SpaceAround,
    verticalAlignment = Alignment.CenterVertically
  ) {
    NavItem(Icons.Default.Home, "Home", selected = currentTab == "Home", onClick = { onTabSelect("Home") })
    NavItem(Icons.Default.Tv, "Live TV", selected = currentTab == "Live TV", onClick = { onTabSelect("Live TV") })
    NavItem(Icons.Default.History, "History", selected = currentTab == "History", onClick = { onTabSelect("History") })
    NavItem(Icons.Default.Person, "Profile", selected = currentTab == "Profile", onClick = { onTabSelect("Profile") })
  }
}

@Composable
fun NavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = Modifier.clickable { onClick() }
  ) {
    Box(
      modifier = Modifier
        .clip(RoundedCornerShape(50))
        .background(if (selected) Cyan600.copy(alpha = 0.2f) else Color.Transparent)
        .padding(horizontal = 20.dp, vertical = 4.dp),
      contentAlignment = Alignment.Center
    ) {
      Icon(icon, contentDescription = label, tint = if (selected) Cyan500 else Slate500, modifier = Modifier.size(24.dp))
    }
    Text(label, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = if (selected) Cyan500 else Slate500)
  }
}
