package com.youbili.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private val BiliPink = Color(0xFFFF6699)
private val Ink = Color(0xFF252525)
private data class Video(
    val id: String, val title: String, val channel: String,
    val views: String, val thumbnail: String, val published: String,
    val description: String = "",
    val channelId: String = "",
    val duration: String = ""
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("channel", channel)
        put("views", views); put("thumbnail", thumbnail); put("published", published); put("description", description); put("channelId", channelId); put("duration", duration)
    }
    companion object {
        fun fromJson(o: JSONObject) = Video(
            o.optString("id"), o.optString("title"), o.optString("channel"),
            o.optString("views"), o.optString("thumbnail"), o.optString("published"), o.optString("description"), o.optString("channelId"), o.optString("duration")
        )
    }
}

private data class FeedPage(val videos: List<Video>, val nextPageToken: String?)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("youbili", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = BiliPink, background = Color(0xFFF6F7F8),
                surface = Color.White, onBackground = Ink
            )) {
                YouBiliApp(
                    initialKey = prefs.getString("api_key", "") ?: "",
                    initialHistory = decodeVideos(prefs.getString("history", "[]") ?: "[]"),
                    initialFavorites = decodeVideos(prefs.getString("favorites", "[]") ?: "[]"),
                    initialHiddenIds = prefs.getStringSet("hidden_video_ids", emptySet()) ?: emptySet(),
                    saveKey = { prefs.edit().putString("api_key", it).apply() },
                    saveHistory = { prefs.edit().putString("history", encodeVideos(it)).apply() },
                    saveFavorites = { prefs.edit().putString("favorites", encodeVideos(it)).apply() },
                    saveHiddenIds = { prefs.edit().putStringSet("hidden_video_ids", it).apply() },
                    shareVideo = { video ->
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "${video.title}\nhttps://www.youtube.com/watch?v=${video.id}")
                        }
                        startActivity(Intent.createChooser(send, "分享视频"))
                    }
                )
            }
        }
    }
}

private fun encodeVideos(videos: List<Video>): String = JSONArray().apply {
    videos.forEach { put(it.toJson()) }
}.toString()
private fun decodeVideos(raw: String): List<Video> = try {
    val a = JSONArray(raw)
    (0 until a.length()).map { Video.fromJson(a.getJSONObject(it)) }
} catch (_: Exception) { emptyList() }

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun YouBiliApp(
    initialKey: String,
    initialHistory: List<Video>,
    initialFavorites: List<Video>,
    initialHiddenIds: Set<String>,
    saveKey: (String) -> Unit,
    saveHistory: (List<Video>) -> Unit,
    saveFavorites: (List<Video>) -> Unit,
    saveHiddenIds: (Set<String>) -> Unit,
    shareVideo: (Video) -> Unit
) {
    var apiKey by remember { mutableStateOf(initialKey) }
    var showSettings by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("首页") }
    var category by remember { mutableStateOf("推荐") }
    var selectedSort by remember { mutableStateOf("综合") }
    var selectedVideo by remember { mutableStateOf<Video?>(null) }
    var previewVideo by remember { mutableStateOf<Video?>(null) }
    var selectedChannel by remember { mutableStateOf<ChannelInfo?>(null) }
    var videos by remember { mutableStateOf(sampleVideos) }
    var history by remember { mutableStateOf(initialHistory) }
    var favorites by remember { mutableStateOf(initialFavorites) }
    var hiddenIds by remember { mutableStateOf(initialHiddenIds) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var nextFeedPageToken by remember { mutableStateOf<String?>(null) }
    var lastAutoLoadToken by remember { mutableStateOf<String?>(null) }
    val feedGridState = rememberLazyGridState()
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Android system Back should unwind in-app pages before leaving the activity.
    BackHandler(enabled = selectedChannel != null || selectedVideo != null || selectedTab != "首页") {
        when {
            selectedChannel != null -> selectedChannel = null
            selectedVideo != null -> selectedVideo = null
            selectedTab != "首页" -> selectedTab = "首页"
        }
    }

    fun rememberWatched(video: Video) {
        history = (listOf(video) + history.filterNot { it.id == video.id }).take(100)
        saveHistory(history)
    }
    fun hideRecommendation(video: Video) {
        hiddenIds = hiddenIds + video.id
        saveHiddenIds(hiddenIds)
        videos = videos.filterNot { it.id == video.id }
    }
    fun toggleFavorite(video: Video) {
        favorites = if (favorites.any { it.id == video.id }) favorites.filterNot { it.id == video.id }
        else listOf(video) + favorites
        saveFavorites(favorites)
    }
    fun personalizeFeed(items: List<Video>): List<Video> {
        // Gentle local affinity: channels already watched/favorited are nudged upward,
        // while the public YouTube ranking remains the primary source ordering.
        val preferredChannels = (history.take(20) + favorites).map { it.channelId }
            .filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        return items.withIndex().sortedWith(
            compareByDescending<IndexedValue<Video>> { preferredChannels[it.value.channelId] ?: 0 }
                .thenBy { it.index }
        ).map { it.value }
    }

    fun runSearch(term: String, sort: String = selectedSort) {
        if (apiKey.isBlank()) { showSettings = true; return }
        scope.launch {
            loading = true; error = ""
            try { videos = personalizeFeed(YouTubeApi.search(apiKey, term, sort).distinctBy { it.id }); nextFeedPageToken = null; selectedTab = "首页" }
            catch (e: Exception) { error = e.message ?: "请求失败，请检查网络和 API Key" }
            finally { loading = false }
        }
    }
    fun loadBiliStyleFeed(tab: String = category) {
        if (apiKey.isBlank()) { showSettings = true; return }
        scope.launch {
            loading = true; error = ""
            try {
                val page = when (tab) {
                    "动画" -> FeedPage(YouTubeApi.search(apiKey, "anime animation", selectedSort).distinctBy { it.id }, null)
                    "热门" -> FeedPage(YouTubeApi.search(apiKey, "trending videos", "播放量").distinctBy { it.id }, null)
                    "音乐" -> YouTubeApi.popularVideosPage(apiKey, "10", null)
                    "游戏" -> YouTubeApi.popularVideosPage(apiKey, "20", null)
                    "科技" -> YouTubeApi.popularVideosPage(apiKey, "28", null)
                    else -> YouTubeApi.popularVideosPage(apiKey, null, null)
                }
                videos = personalizeFeed(page.videos.filterNot { it.id in hiddenIds }.distinctBy { it.id })
                nextFeedPageToken = page.nextPageToken
                lastAutoLoadToken = null
                selectedTab = "首页"
            } catch (e: Exception) {
                error = e.message ?: "推荐内容加载失败，请检查网络和 API Key"
            } finally { loading = false }
        }
    }

    fun loadMoreFeed() {
        val token = nextFeedPageToken ?: return
        if (apiKey.isBlank() || loading || loadingMore) return
        scope.launch {
            loadingMore = true
            error = ""
            try {
                val categoryId = when (category) { "音乐" -> "10"; "游戏" -> "20"; "科技" -> "28"; else -> null }
                val page = YouTubeApi.popularVideosPage(apiKey, categoryId, token)
                val merged = (videos + page.videos).filterNot { it.id in hiddenIds }.distinctBy { it.id }
                videos = personalizeFeed(merged)
                nextFeedPageToken = page.nextPageToken
            } catch (e: Exception) {
                error = e.message ?: "加载更多失败"
            } finally { loadingMore = false }
        }
    }

    val pullRefreshState = rememberPullRefreshState(refreshing = loading, onRefresh = { loadBiliStyleFeed(category) })

    // Load the next page as the user approaches the end of the grid. A token guard
    // prevents the same page from being requested repeatedly while the list is idle.
    LaunchedEffect(feedGridState, selectedTab, nextFeedPageToken, loadingMore, videos.size) {
        if (selectedTab != "首页" || nextFeedPageToken == null || loadingMore) return@LaunchedEffect
        val requestToken = nextFeedPageToken
        snapshotFlow { feedGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collectLatest { lastVisible ->
                val total = feedGridState.layoutInfo.totalItemsCount
                if (total > 0 && lastVisible >= total - 4 && requestToken != lastAutoLoadToken) {
                    lastAutoLoadToken = requestToken
                    loadMoreFeed()
                }
            }
    }

    LaunchedEffect(apiKey) {
        if (apiKey.isNotBlank() && videos == sampleVideos) loadBiliStyleFeed("推荐")
    }

    Scaffold(
        containerColor = Color(0xFFF6F7F8),
        topBar = {
            Column(Modifier.background(Color.White)) {
                Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("YouBili", color = BiliPink, fontSize = 25.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.weight(1f).height(50.dp),
                        placeholder = { Text("搜索视频、UP主", fontSize = 13.sp) },
                        singleLine = true, shape = RoundedCornerShape(24.dp),
                        trailingIcon = { IconButton(onClick = { runSearch(query.ifBlank { "popular videos" }) }) {
                            Icon(Icons.Default.Search, "搜索")
                        } }
                    )
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "设置") }
                }
                if (selectedTab == "首页") {
                    Row(Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        listOf("推荐", "热门", "动画", "音乐", "游戏", "科技").forEach { item ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    category = item
                                    selectedSort = "综合"
                                    loadBiliStyleFeed(item)
                                }.padding(horizontal = 13.dp, vertical = 5.dp)) {
                                Text(item, color = if (category == item) BiliPink else Color.DarkGray,
                                    fontWeight = if (category == item) FontWeight.Bold else FontWeight.Normal)
                                Spacer(Modifier.height(4.dp))
                                Box(Modifier.width(19.dp).height(3.dp).background(
                                    if (category == item) BiliPink else Color.Transparent, RoundedCornerShape(2.dp)))
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                listOf(
                    Triple("首页", Icons.Default.Home, "首页"),
                    Triple("动态", Icons.Default.Explore, "动态"),
                    Triple("收藏", Icons.Default.Star, "收藏"),
                    Triple("历史", Icons.Default.History, "历史"),
                    Triple("我的", Icons.Default.Person, "我的")
                ).forEach { (label, icon, route) ->
                    NavigationBarItem(selected = selectedTab == route,
                        onClick = { selectedTab = route; selectedVideo = null },
                        icon = { Icon(icon, contentDescription = label) }, label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = BiliPink,
                            selectedTextColor = BiliPink, indicatorColor = Color(0xFFFFE7EF)))
                }
            }
        }
    ) { padding ->
        if (selectedChannel != null) {
            ChannelPage(channel = selectedChannel!!, apiKey = apiKey, onBack = { selectedChannel = null },
                onVideo = { video -> rememberWatched(video); selectedVideo = video; selectedChannel = null },
                modifier = Modifier.padding(padding))
        } else if (selectedVideo != null) {
            val current = selectedVideo!!
            VideoPage(current, apiKey = apiKey, isFavorite = favorites.any { it.id == current.id },
                onBack = { selectedVideo = null }, onFavorite = { toggleFavorite(current) },
                onShare = { shareVideo(current) },
                onChannel = { if (current.channelId.isNotBlank()) selectedChannel = ChannelInfo(current.channelId, current.channel) },
                modifier = Modifier.padding(padding))
        } else {
            val visibleVideos = when (selectedTab) {
                "收藏" -> favorites
                "历史" -> history
                else -> videos.filterNot { it.id in hiddenIds }.distinctBy { it.id }
            }
            Box(Modifier.fillMaxSize().padding(padding)) {
              Column(Modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
                if (selectedTab == "首页") {
                    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        Text("排序", color = Color.Gray, fontSize = 11.sp)
                        Spacer(Modifier.width(8.dp))
                        listOf("综合", "最新", "播放量").forEach { sort ->
                            Text(sort, color = if (selectedSort == sort) BiliPink else Color.Gray,
                                fontSize = 12.sp, modifier = Modifier.clickable {
                                    selectedSort = sort
                                    val term = query.ifBlank { when (category) {
                                        "动画" -> "anime animation"; "音乐" -> "music videos"
                                        "游戏" -> "gaming videos"; "科技" -> "technology videos"
                                        else -> "trending videos"
                                    } }
                                    if (category == "动画") runSearch(term, sort) else loadBiliStyleFeed(category)
                                }.padding(horizontal = 8.dp, vertical = 5.dp))
                        }
                        IconButton(onClick = { loadBiliStyleFeed(category) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新推荐", tint = BiliPink, modifier = Modifier.size(19.dp))
                        }
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = BiliPink)
                if (error.isNotBlank()) Text(error, color = Color(0xFFB00020), modifier = Modifier.padding(12.dp), fontSize = 12.sp)
                if (selectedTab == "动态") {
                    EmptyState("动态功能将在后续版本接入", "当前版本可以搜索、播放、收藏并记录观看历史。")
                } else if (visibleVideos.isEmpty()) {
                    EmptyState(if (selectedTab == "收藏") "还没有收藏的视频" else if (selectedTab == "历史") "还没有观看记录" else "暂无视频", "配置 YouTube API Key 后即可搜索视频。")
                } else {
                    LazyVerticalGrid(
                        state = feedGridState,
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        gridItems(visibleVideos, key = { it.id }) { video ->
                            VideoCard(video, Modifier.fillMaxWidth(), onNotInterested = { hideRecommendation(video) },
                                onPreview = { previewVideo = video }) {
                                rememberWatched(video); selectedVideo = video
                            }
                        }
                        if (selectedTab == "首页" && (loadingMore || nextFeedPageToken == null)) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                                    if (loadingMore) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BiliPink)
                                            Text("正在加载更多", color = Color.Gray, fontSize = 12.sp)
                                        }
                                    } else {
                                        Text("暂时没有更多内容", color = Color.Gray, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
              }
              PullRefreshIndicator(loading, pullRefreshState, Modifier.align(Alignment.TopCenter),
                  backgroundColor = Color.White, contentColor = BiliPink)
            }
        }
    }

    previewVideo?.let { video ->
        AlertDialog(
            onDismissRequest = { previewVideo = null },
            title = { Text("视频预览") },
            text = {
                Column {
                    AsyncImage(model = video.thumbnail, contentDescription = video.title,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Spacer(Modifier.height(10.dp))
                    Text(video.title, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(video.channel, color = Color.Gray, fontSize = 12.sp)
                    Text("预览仅显示封面和视频信息，不会自动播放。", color = Color.Gray, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = {
                rememberWatched(video); selectedVideo = video; previewVideo = null
            }) { Text("进入播放", color = BiliPink) } },
            dismissButton = { TextButton(onClick = { previewVideo = null }) { Text("关闭") } }
        )
    }

    if (showSettings) {
        var draft by remember { mutableStateOf(apiKey) }
        AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("YouTube API 设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("填写自己的 YouTube Data API v3 Key。请在 Google Cloud Console 中启用 YouTube Data API v3，并限制此 Key 的 API 使用范围。", fontSize = 13.sp)
                    OutlinedTextField(draft, { draft = it }, label = { Text("API Key") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { apiKey = draft.trim(); saveKey(apiKey); showSettings = false }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("取消") } })
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.VideoLibrary, null, tint = BiliPink, modifier = Modifier.size(54.dp))
        Spacer(Modifier.height(12.dp)); Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp)); Text(subtitle, color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun VideoCard(
    video: Video,
    modifier: Modifier = Modifier,
    onNotInterested: () -> Unit = {},
    onPreview: () -> Unit = {},
    onClick: () -> Unit
) {
    var menuExpanded by remember(video.id) { mutableStateOf(false) }
    Column(modifier.background(Color.White, RoundedCornerShape(9.dp))) {
        Box {
            AsyncImage(model = video.thumbnail, contentDescription = video.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).combinedClickable(
                    onClick = onClick,
                    onLongClick = onPreview
                ))
            Text("▶", color = Color.White, modifier = Modifier.align(Alignment.Center)
                .background(Color(0x55000000), RoundedCornerShape(30.dp))
                .clickable { onClick() }.padding(horizontal = 9.dp, vertical = 4.dp), fontSize = 13.sp)
            if (video.duration.isNotBlank()) {
                Text(video.duration, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp))
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多操作", tint = Color.White,
                        modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(20.dp)).padding(3.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("不感兴趣") }, leadingIcon = {
                        Icon(Icons.Default.VisibilityOff, contentDescription = null)
                    }, onClick = { menuExpanded = false; onNotInterested() })
                    DropdownMenuItem(text = { Text("打开视频") }, leadingIcon = {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                    }, onClick = { menuExpanded = false; onClick() })
                }
            }
        }
        Column(Modifier.clickable { onClick() }.padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(video.title, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2, color = Ink)
            Spacer(Modifier.height(7.dp))
            Text(video.channel, color = Color(0xFF777777), fontSize = 11.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF999999), modifier = Modifier.size(13.dp))
                Text(video.views.removeSuffix(" 播放"), color = Color(0xFF999999), fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

private data class ChannelInfo(val id: String, val fallbackTitle: String)
private data class ChannelDetails(
    val id: String, val title: String, val description: String,
    val thumbnail: String, val subscribers: String, val videoCount: String
)

@Composable
private fun ChannelPage(channel: ChannelInfo, apiKey: String, onBack: () -> Unit,
                        onVideo: (Video) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var details by remember(channel.id) { mutableStateOf<ChannelDetails?>(null) }
    var videos by remember(channel.id) { mutableStateOf<List<Video>>(emptyList()) }
    var loading by remember(channel.id) { mutableStateOf(false) }
    var error by remember(channel.id) { mutableStateOf("") }
    var section by remember(channel.id) { mutableStateOf("投稿") }
    var showChannelSearch by remember(channel.id) { mutableStateOf(false) }

    // A nested channel search is one level deeper than the channel profile.
    BackHandler(enabled = showChannelSearch) { showChannelSearch = false }

    LaunchedEffect(channel.id, apiKey) {
        if (apiKey.isBlank()) { error = "请先配置 YouTube Data API Key"; return@LaunchedEffect }
        loading = true; error = ""
        try {
            details = YouTubeApi.channelDetails(apiKey, channel.id)
            videos = YouTubeApi.channelVideos(apiKey, channel.id)
        } catch (e: Exception) { error = e.message ?: "频道加载失败" }
        finally { loading = false }
    }

    val current = details
    if (showChannelSearch) {
        ChannelSearchPage(
            channel = channel,
            channelTitle = current?.title ?: channel.fallbackTitle,
            apiKey = apiKey,
            onBack = { showChannelSearch = false },
            onVideo = onVideo,
            modifier = modifier
        )
        return
    }
    Column(modifier.fillMaxSize().background(Color(0xFFFAFAFC))) {
        // Bilibili-style profile app bar
        Row(
            Modifier.fillMaxWidth().height(54.dp).background(Color(0xFFFAFAFC)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回", tint = Ink) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showChannelSearch = true }) {
                Icon(Icons.Default.Search, "搜索该频道", tint = Ink)
            }
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/channel/${channel.id}")
                }
                context.startActivity(Intent.createChooser(send, "分享频道"))
            }) { Icon(Icons.Default.MoreVert, "更多", tint = Ink) }
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = BiliPink)
        if (error.isNotBlank()) {
            Text(error, color = Color(0xFFB00020), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 12.sp)
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                // Avatar and channel statistics
                Row(
                    Modifier.fillMaxWidth().padding(start = 30.dp, end = 24.dp, top = 42.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = current?.thumbnail,
                        contentDescription = "频道头像",
                        modifier = Modifier.size(104.dp).background(Color(0xFFFFE7EF), RoundedCornerShape(60.dp))
                    )
                    Spacer(Modifier.width(22.dp))
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            ChannelStat("—", "关注")
                            ChannelStat(current?.subscribers ?: "—", "粉丝")
                            ChannelStat("—", "获赞")
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier.weight(1f).height(42.dp)
                                    .background(Color(0xFFF0F0F2), RoundedCornerShape(24.dp))
                                    .clickable {
                                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/channel/${channel.id}"))
                                        context.startActivity(intent)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("订阅频道", color = Color(0xFF60666B), fontWeight = FontWeight.SemiBold)
                            }
                            Box(
                                Modifier.weight(1f).height(42.dp)
                                    .background(Color(0xFFEAF6FA), RoundedCornerShape(24.dp))
                                    .clickable {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/channel/${channel.id}")
                                        }
                                        context.startActivity(Intent.createChooser(send, "分享频道"))
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("分享频道", color = Color(0xFF08758C), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 8.dp)) {
                    Text(
                        current?.title ?: channel.fallbackTitle,
                        color = BiliPink, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(13.dp))
                    Text("YouTube Channel", color = Color(0xFF555B60), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        current?.description?.ifBlank { "这个频道还没有填写简介。" } ?: "正在加载频道简介……",
                        color = Ink, fontSize = 14.sp, lineHeight = 21.sp,
                        maxLines = 4, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
            item {
                Column(Modifier.fillMaxWidth()) {
                    listOf("Ta的动态", "Ta的投稿", "Ta的收藏", "Ta的专栏", "Ta的合集").forEach { label ->
                        Row(
                            Modifier.fillMaxWidth().height(66.dp)
                                .clickable { section = label.removePrefix("Ta的") }
                                .padding(horizontal = 30.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowForward, null, tint = Color(0xFF4C5358), modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        when (section) {
                            "动态" -> "频道动态"
                            "投稿" -> "Ta的投稿"
                            "收藏" -> "公开收藏"
                            "专栏" -> "频道介绍"
                            else -> "频道视频"
                        },
                        fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f)
                    )
                    Text("${videos.size} 个视频", color = Color.Gray, fontSize = 11.sp)
                }
            }
            when (section) {
                "投稿", "合集" -> {
                    items(videos.chunked(2)) { pair ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            pair.forEach { v -> VideoCard(v, Modifier.weight(1f)) { onVideo(v) } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                "动态" -> item {
                    EmptyState("暂不支持读取频道动态", "YouTube Data API 不提供与 B 站动态完全对应的个人动态流。")
                }
                "收藏" -> item {
                    EmptyState("没有可读取的公开收藏", "YouTube 用户收藏内容通常需要账号授权，部分列表也可能是私有的。")
                }
                else -> item {
                    EmptyState("频道简介", current?.description?.ifBlank { "暂无频道简介" } ?: "正在读取频道资料。")
                }
            }
        }
    }
}

@Composable
private fun ChannelSearchPage(
    channel: ChannelInfo,
    channelTitle: String,
    apiKey: String,
    onBack: () -> Unit,
    onVideo: (Video) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember(channel.id) { mutableStateOf("") }
    var results by remember(channel.id) { mutableStateOf<List<Video>>(emptyList()) }
    var loading by remember(channel.id) { mutableStateOf(false) }
    var error by remember(channel.id) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun search() {
        val term = query.trim()
        if (term.isBlank()) return
        if (apiKey.isBlank()) { error = "请先在设置中配置 YouTube Data API Key"; return }
        scope.launch {
            loading = true
            error = ""
            keyboard?.hide()
            try { results = YouTubeApi.searchChannel(apiKey, channel.id, term) }
            catch (e: Exception) { error = e.message ?: "搜索失败" }
            finally { loading = false }
        }
    }

    LaunchedEffect(channel.id) { focusRequester.requestFocus() }

    Column(modifier.fillMaxSize().background(Color(0xFFFAFAFC))) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回", tint = Ink) }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f).height(54.dp).focusRequester(focusRequester),
                placeholder = { Text("搜索", color = Color(0xFF555B60)) },
                singleLine = true,
                shape = RoundedCornerShape(0.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    cursorColor = Color(0xFF08758C)
                ),
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = ""; results = emptyList(); error = "" }) {
                        Icon(Icons.Default.Close, "清空", tint = Color.Gray)
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() })
            )
            IconButton(onClick = { search() }) { Icon(Icons.Default.Search, "搜索", tint = Ink) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = BiliPink)
        if (error.isNotBlank()) Text(error, color = Color(0xFFB00020), fontSize = 12.sp, modifier = Modifier.padding(16.dp))
        if (results.isEmpty() && !loading && error.isBlank()) {
            Box(Modifier.fillMaxSize().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (query.isBlank()) "搜索「$channelTitle」的动态、视频" else "搜索「$query」的动态、视频",
                    color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                )
            }
        } else if (results.isNotEmpty()) {
            LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(results.chunked(2)) { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        pair.forEach { video -> VideoCard(video, Modifier.weight(1f)) { onVideo(video) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp, color = Color(0xFF33383C), fontWeight = FontWeight.SemiBold)
    }
}

private data class VideoComment(val author: String, val text: String, val likes: Int, val published: String)
private data class CommentsPage(val comments: List<VideoComment>, val nextPageToken: String?)

@Composable
private fun DetailAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFFFFE8F0) else Color(0xFFF4F5F7))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) BiliPink else Ink,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = if (selected) BiliPink else Ink,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun VideoPage(video: Video, apiKey: String, isFavorite: Boolean, onBack: () -> Unit,
                      onFavorite: () -> Unit, onShare: () -> Unit, onChannel: () -> Unit, modifier: Modifier = Modifier) {
    val commentScope = rememberCoroutineScope()
    val detailScrollState = rememberScrollState()
    var channelDetails by remember(video.channelId) { mutableStateOf<ChannelDetails?>(null) }
    var channelInfoLoading by remember(video.channelId) { mutableStateOf(false) }
    var comments by remember(video.id) { mutableStateOf<List<VideoComment>>(emptyList()) }
    var commentsLoading by remember(video.id) { mutableStateOf(false) }
    var commentsError by remember(video.id) { mutableStateOf("") }
    var commentsPageToken by remember(video.id) { mutableStateOf<String?>(null) }
    var hasMoreComments by remember(video.id) { mutableStateOf(false) }
    suspend fun loadComments(nextPage: Boolean) {
        if (apiKey.isBlank() || commentsLoading) return
        commentsLoading = true
        commentsError = ""
        try {
            val page = YouTubeApi.comments(apiKey, video.id, if (nextPage) commentsPageToken else null)
            comments = if (nextPage) comments + page.comments else page.comments
            commentsPageToken = page.nextPageToken
            hasMoreComments = !page.nextPageToken.isNullOrBlank()
        } catch (e: Exception) { commentsError = e.message ?: "评论加载失败" }
        finally { commentsLoading = false }
    }
    LaunchedEffect(video.id, apiKey) {
        comments = emptyList(); commentsPageToken = null; hasMoreComments = false
        if (apiKey.isNotBlank()) loadComments(false)
        else commentsError = "配置 YouTube API Key 后可读取公开评论。"
    }
    LaunchedEffect(video.channelId, apiKey) {
        channelDetails = null
        if (apiKey.isNotBlank() && video.channelId.isNotBlank()) {
            channelInfoLoading = true
            try { channelDetails = YouTubeApi.channelDetails(apiKey, video.channelId) }
            catch (_: Exception) { channelDetails = null }
            finally { channelInfoLoading = false }
        } else channelInfoLoading = false
    }
    LaunchedEffect(video.id, detailScrollState) {
        snapshotFlow { Triple(detailScrollState.value, detailScrollState.maxValue, hasMoreComments && !commentsLoading) }
            .distinctUntilChanged()
            .collect { (scrollY, maxY, canLoad) ->
                // Do not use collectLatest here: changing commentsLoading emits a new
                // snapshot and would cancel the in-flight suspend request.
                // loadComments has its own loading guard, so a regular collect is safe.
                if (maxY > 0 && scrollY >= maxY - 500 && canLoad) loadComments(true)
            }
    }
    Column(modifier.fillMaxSize().background(Color.White).verticalScroll(detailScrollState)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
            Text("视频播放", fontWeight = FontWeight.Bold)
        }
        YouTubePlayer(video.id)
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(video.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(video.views, color = Color.Gray, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            // Creator identity card: show only public channel fields returned by YouTube.
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0xFFF7F7F9), RoundedCornerShape(12.dp))
                    .clickable(enabled = video.channelId.isNotBlank()) { onChannel() }
                    .padding(11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!channelDetails?.thumbnail.isNullOrBlank()) {
                    AsyncImage(
                        model = channelDetails?.thumbnail,
                        contentDescription = "频道头像",
                        modifier = Modifier.size(46.dp).clip(CircleShape)
                    )
                } else {
                    Box(Modifier.size(46.dp).background(Color(0xFFFFE4ED), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = BiliPink, modifier = Modifier.size(25.dp))
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(channelDetails?.title ?: video.channel, color = Ink, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        when {
                            channelDetails != null -> "${channelDetails!!.subscribers} 位订阅者 · ${channelDetails!!.videoCount} 个视频"
                            channelInfoLoading -> "正在读取频道资料…"
                            video.channelId.isBlank() -> "频道资料暂不可用"
                            else -> "YouTube 频道"
                        },
                        color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (video.channelId.isNotBlank()) {
                    Text("进入空间  ›", color = BiliPink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DetailAction(
                    label = if (isFavorite) "已加入本地收藏" else "收藏",
                    icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    selected = isFavorite,
                    modifier = Modifier.weight(1f),
                    onClick = onFavorite
                )
                DetailAction(
                    label = "分享视频",
                    icon = Icons.Default.Share,
                    selected = false,
                    modifier = Modifier.weight(1f),
                    onClick = onShare
                )
            }
            Text(
                "收藏仅保存在本机；点赞、订阅和评论发布尚需 YouTube OAuth 授权。",
                color = Color.Gray, fontSize = 10.sp,
                modifier = Modifier.padding(top = 7.dp)
            )
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            Text("简介", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(video.description.ifBlank { "暂无简介" }, color = Color.Gray, fontSize = 13.sp)
            if (video.published.isNotBlank()) Text("发布时间：${video.published.take(10)}", color = Color.Gray, fontSize = 11.sp)
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("评论区", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${comments.size} 条", color = Color.Gray, fontSize = 11.sp)
            }
            if (commentsError.isNotBlank()) {
                Text(commentsError, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                if (apiKey.isNotBlank()) TextButton(onClick = { commentScope.launch { loadComments(false) } }) {
                    Text("重新加载评论", color = BiliPink)
                }
            }
            if (comments.isEmpty() && !commentsLoading && commentsError.isBlank()) Text("暂无公开评论", color = Color.Gray, fontSize = 12.sp)
            comments.forEach { comment ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(comment.author, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text("${comment.likes} 赞 · ${comment.published.take(10)}", color = Color.Gray, fontSize = 10.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(comment.text, fontSize = 13.sp, color = Ink)
                    }
                    HorizontalDivider(color = Color(0xFFEEEEEE))
            }
            if (commentsLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = BiliPink)
            if (hasMoreComments && !commentsLoading) {
                Text("继续向下滑动以加载更多评论", color = Color.Gray, fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
            }
            Text("评论发布和点赞需要 Google OAuth 授权；当前版本仅读取公开评论。", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun YouTubePlayer(videoId: String) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var fullScreenView by remember(videoId) { mutableStateOf<View?>(null) }
    var fullScreenCallback by remember(videoId) { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var previousOrientation by remember(videoId) { mutableIntStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }

    if (fullScreenView != null) {
        Dialog(
            onDismissRequest = {
                fullScreenCallback?.onCustomViewHidden()
                fullScreenCallback = null
                fullScreenView = null
                activity?.requestedOrientation = previousOrientation
            },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        val view = fullScreenView
                        if (view != null) {
                            (view.parent as? ViewGroup)?.removeView(view)
                            addView(view, FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            ))
                        }
                    }
                }
            )
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = true
                webChromeClient = object : WebChromeClient() {
                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        if (view == null) return
                        previousOrientation = activity?.requestedOrientation
                            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        fullScreenView = view
                        fullScreenCallback = callback
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }

                    override fun onHideCustomView() {
                        fullScreenCallback?.onCustomViewHidden()
                        fullScreenCallback = null
                        fullScreenView = null
                        activity?.requestedOrientation = previousOrientation
                    }
                }
                webViewClient = WebViewClient()
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { web ->
            val html = """
                <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1"></head>
                <body style="margin:0;background:#000;overflow:hidden">
                <iframe width="100%" height="100%" style="position:absolute;inset:0"
                src="https://www.youtube.com/embed/$videoId?playsinline=1&rel=0&enablejsapi=1"
                title="YouTube video player" frameborder="0"
                allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
                referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe>
                </body></html>
            """.trimIndent()
            if (web.tag != videoId) {
                web.tag = videoId
                web.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "UTF-8", null)
            }
        },
        onRelease = { web ->
            // Explicitly release the embedded browser when the player leaves composition.
            web.stopLoading()
            web.loadUrl("about:blank")
            web.webChromeClient = null
            web.webViewClient = WebViewClient()
            web.destroy()
        }
    )
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private object YouTubeApi {
    suspend fun search(key: String, q: String, sort: String): List<Video> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(q, "UTF-8")
        val order = when (sort) { "最新" -> "date"; "播放量" -> "viewCount"; else -> "relevance" }
        val url = URL("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=25&order=$order&q=$encoded&key=$key")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 15000
        }
        try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) {
                val message = try { JSONObject(body).getJSONObject("error").optString("message") } catch (_: Exception) { body }
                throw IllegalStateException("YouTube API 错误 ${connection.responseCode}: $message")
            }
            val items = JSONObject(body).getJSONArray("items")
            (0 until items.length()).mapNotNull { i ->
                val item = items.getJSONObject(i); val id = item.getJSONObject("id").optString("videoId")
                if (id.isBlank()) return@mapNotNull null
                val snippet = item.getJSONObject("snippet"); val thumbs = snippet.getJSONObject("thumbnails")
                val thumb = when {
                    thumbs.has("high") -> thumbs.getJSONObject("high").getString("url")
                    thumbs.has("medium") -> thumbs.getJSONObject("medium").getString("url")
                    else -> thumbs.getJSONObject("default").getString("url")
                }
                Video(id, snippet.optString("title"), snippet.optString("channelTitle"),
                    "YouTube 视频", thumb, snippet.optString("publishedAt"), snippet.optString("description"), snippet.optString("channelId"))
            }
        } finally { connection.disconnect() }
    }

    // Bilibili-like discovery feed: use YouTube's public mostPopular chart, grouped by video category.
    // This is a regional/popular feed, not a personalized recommendation model.
    suspend fun popularVideosPage(key: String, categoryId: String?, pageToken: String?): FeedPage = withContext(Dispatchers.IO) {
        val category = categoryId?.let { "&videoCategoryId=$it" } ?: ""
        val page = pageToken?.let { "&pageToken=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
        val url = URL("https://www.googleapis.com/youtube/v3/videos?part=snippet,statistics,contentDetails&chart=mostPopular&maxResults=24$category$page&key=$key")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 15000
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                val message = try { JSONObject(body).getJSONObject("error").optString("message") } catch (_: Exception) { body }
                throw IllegalStateException("推荐内容 API 错误 $code: $message")
            }
            val items = JSONObject(body).getJSONArray("items")
            val parsed = (0 until items.length()).mapNotNull { i ->
                val item = items.getJSONObject(i)
                val id = item.optString("id")
                if (id.isBlank()) return@mapNotNull null
                val snippet = item.getJSONObject("snippet")
                val thumbs = snippet.optJSONObject("thumbnails") ?: JSONObject()
                val thumb = when {
                    thumbs.has("high") -> thumbs.getJSONObject("high").optString("url")
                    thumbs.has("medium") -> thumbs.getJSONObject("medium").optString("url")
                    else -> thumbs.optJSONObject("default")?.optString("url") ?: ""
                }
                val count = item.optJSONObject("statistics")?.optString("viewCount", "") ?: ""
                val views = if (count.isBlank()) "YouTube 视频" else "${formatViews(count)} 播放"
                val isoDuration = item.optJSONObject("contentDetails")?.optString("duration", "") ?: ""
                Video(id, snippet.optString("title"), snippet.optString("channelTitle"), views,
                    thumb, snippet.optString("publishedAt"), snippet.optString("description"), snippet.optString("channelId"), formatDuration(isoDuration))
            }
            FeedPage(parsed, JSONObject(body).optString("nextPageToken").takeIf { it.isNotBlank() })
        } finally { connection.disconnect() }
    }

    private fun formatDuration(raw: String): String {
        if (raw.isBlank()) return ""
        val match = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matchEntire(raw) ?: return ""
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val minutes = match.groupValues[2].toIntOrNull() ?: 0
        val seconds = match.groupValues[3].toIntOrNull() ?: 0
        return if (hours > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }

    private fun formatViews(raw: String): String = try {
        val n = raw.toLong()
        when {
            n >= 100_000_000 -> String.format(java.util.Locale.US, "%.1f亿", n / 100_000_000.0)
            n >= 10_000 -> String.format(java.util.Locale.US, "%.1f万", n / 10_000.0)
            else -> n.toString()
        }
    } catch (_: Exception) { raw }

    suspend fun searchChannel(key: String, channelId: String, query: String): List<Video> = withContext(Dispatchers.IO) {
        val id = URLEncoder.encode(channelId, "UTF-8")
        val q = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=25&order=relevance&channelId=$id&q=$q&key=$key")
        val connection = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 15000 }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                val message = try { JSONObject(body).getJSONObject("error").optString("message") } catch (_: Exception) { body }
                throw IllegalStateException("频道搜索 API 错误 $code: $message")
            }
            val items = JSONObject(body).getJSONArray("items")
            (0 until items.length()).mapNotNull { i ->
                val item = items.getJSONObject(i)
                val videoId = item.getJSONObject("id").optString("videoId")
                if (videoId.isBlank()) return@mapNotNull null
                val snippet = item.getJSONObject("snippet")
                val thumbs = snippet.optJSONObject("thumbnails") ?: JSONObject()
                val thumb = when { thumbs.has("high") -> thumbs.getJSONObject("high").optString("url")
                    thumbs.has("medium") -> thumbs.getJSONObject("medium").optString("url")
                    else -> thumbs.optJSONObject("default")?.optString("url") ?: "" }
                Video(videoId, snippet.optString("title"), snippet.optString("channelTitle"), "YouTube 视频",
                    thumb, snippet.optString("publishedAt"), snippet.optString("description"), snippet.optString("channelId"))
            }
        } finally { connection.disconnect() }
    }

    suspend fun channelDetails(key: String, channelId: String): ChannelDetails = withContext(Dispatchers.IO) {
        val id = URLEncoder.encode(channelId, "UTF-8")
        val url = URL("https://www.googleapis.com/youtube/v3/channels?part=snippet,statistics&id=$id&key=$key")
        val connection = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 15000 }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("频道 API 错误 $code")
            val items = JSONObject(body).getJSONArray("items")
            if (items.length() == 0) throw IllegalStateException("找不到该频道")
            val item = items.getJSONObject(0); val snippet = item.getJSONObject("snippet"); val stats = item.optJSONObject("statistics") ?: JSONObject()
            val thumbs = snippet.optJSONObject("thumbnails") ?: JSONObject()
            val image = when { thumbs.has("high") -> thumbs.getJSONObject("high").optString("url")
                thumbs.has("medium") -> thumbs.getJSONObject("medium").optString("url")
                thumbs.has("default") -> thumbs.getJSONObject("default").optString("url")
                else -> "" }
            ChannelDetails(channelId, snippet.optString("title"), snippet.optString("description"), image,
                stats.optString("subscriberCount", "隐藏"), stats.optString("videoCount", "—"))
        } finally { connection.disconnect() }
    }

    suspend fun channelVideos(key: String, channelId: String): List<Video> = withContext(Dispatchers.IO) {
        val id = URLEncoder.encode(channelId, "UTF-8")
        val url = URL("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&order=date&maxResults=20&channelId=$id&key=$key")
        val connection = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 15000 }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("频道视频 API 错误 $code")
            val items = JSONObject(body).getJSONArray("items")
            (0 until items.length()).mapNotNull { i ->
                val item = items.getJSONObject(i); val videoId = item.getJSONObject("id").optString("videoId")
                if (videoId.isBlank()) return@mapNotNull null
                val snippet = item.getJSONObject("snippet"); val thumbs = snippet.getJSONObject("thumbnails")
                val thumb = when { thumbs.has("high") -> thumbs.getJSONObject("high").optString("url")
                    thumbs.has("medium") -> thumbs.getJSONObject("medium").optString("url")
                    else -> thumbs.getJSONObject("default").optString("url") }
                Video(videoId, snippet.optString("title"), snippet.optString("channelTitle"), "YouTube 视频",
                    thumb, snippet.optString("publishedAt"), snippet.optString("description"), snippet.optString("channelId"))
            }
        } finally { connection.disconnect() }
    }

    suspend fun comments(key: String, videoId: String, pageToken: String? = null): CommentsPage = withContext(Dispatchers.IO) {
        val encodedId = URLEncoder.encode(videoId, "UTF-8")
        val page = if (pageToken.isNullOrBlank()) "" else "&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}" 
        val url = URL("https://www.googleapis.com/youtube/v3/commentThreads?part=snippet&videoId=$encodedId&maxResults=20&order=relevance&textFormat=plainText$page&key=$key")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 15000
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                val message = try { JSONObject(body).getJSONObject("error").optString("message") } catch (_: Exception) { body }
                throw IllegalStateException("评论 API 错误 $code: $message")
            }
            val items = JSONObject(body).getJSONArray("items")
            val parsed = (0 until items.length()).mapNotNull { index ->
                val top = items.getJSONObject(index).getJSONObject("snippet")
                    .getJSONObject("topLevelComment").getJSONObject("snippet")
                VideoComment(top.optString("authorDisplayName", "YouTube 用户"),
                    top.optString("textDisplay"), top.optInt("likeCount"), top.optString("publishedAt"))
            }
            CommentsPage(parsed, JSONObject(body).optString("nextPageToken").takeIf { it.isNotBlank() })
        } finally { connection.disconnect() }
    }
}

private val sampleVideos = listOf(
    Video("dQw4w9WgXcQ", "YouBili 已就绪：配置 API Key 后搜索真实视频", "YouBili", "演示内容", "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", ""),
    Video("M7lc1UVf-VE", "YouTube Player API 演示视频", "YouTube Developers", "演示内容", "https://i.ytimg.com/vi/M7lc1UVf-VE/mqdefault.jpg", ""),
    Video("aqz-KE-bpKQ", "Big Buck Bunny - Official Sample", "Blender", "演示内容", "https://i.ytimg.com/vi/aqz-KE-bpKQ/mqdefault.jpg", ""),
    Video("ScMzIvxBSi4", "Sample video", "Sample Channel", "演示内容", "https://i.ytimg.com/vi/ScMzIvxBSi4/mqdefault.jpg", "")
)
