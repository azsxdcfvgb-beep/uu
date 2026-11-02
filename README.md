# YouBili Android — Phase 4

Bilibili-inspired Android client starter using Kotlin, Jetpack Compose, YouTube Data API v3, and the official YouTube embedded player.

## Phase 2 features
- Bilibili-inspired header, category row, two-column video feed and bottom navigation
- Category taps issue YouTube Data API searches (recommendation/popular, animation, music, gaming, technology search terms)
- Search videos through YouTube Data API v3
- Official YouTube embedded player in the video page
- Local watch history (up to 100 entries) persisted with SharedPreferences
- Local favorites persisted with SharedPreferences
- Android share sheet for video links
- API key configuration dialog; key is stored locally in app preferences
- API error response messages surfaced in the UI
- Search sorting: relevance, newest, and view-count order
- Video descriptions and publication dates displayed
- Public top-level YouTube comments loaded through `commentThreads`
- Comment pagination using YouTube API page tokens
- Retry action for comment-loading failures
- Scrollable video details and comments screen
- Full-screen embedded-player custom-view handling with landscape orientation support
- Avoid unnecessary WebView reloads when Compose recomposes the player

## Build
1. Open this folder in Android Studio (recent stable version).
2. Let Gradle sync and install the Android SDK platform/build tools requested by the project.
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. Install the debug APK on your Android device.

## Configure YouTube API
1. Create a project in Google Cloud Console.
2. Enable **YouTube Data API v3**.
3. Create an API key.
4. In YouBili, tap the gear icon and paste the key.
5. Restrict the key to YouTube Data API v3 and apply suitable Android application restrictions after registering the package/signing certificate.

Search uses `search.list`; YouTube API quota limits apply. Avoid repeatedly searching the same terms. Category names are translated into search queries, not guaranteed YouTube regional/category feeds.

## Phase 4 limitations
- Full-screen behavior depends on YouTube embedded-player support and device WebView implementation; it still needs physical-device testing.

## Phase 3 limitations
- Comments are read-only. Posting, replying, and liking require OAuth 2.0 and the relevant authorized YouTube API scopes.
- The current comment view shows top-level comments only; replies are not expanded.
- Category labels map to text searches; they are not official Bilibili-style channel feeds.

## Important limitations
- This is an Android Studio source project, not a prebuilt APK. It must be compiled with Android Studio/Gradle.
- YouTube Data API returns metadata and video IDs, not raw video stream URLs. Playback uses the YouTube embedded player.
- Favorites and history are local to this installation; they are not synchronized with a YouTube account.
- YouTube account actions such as subscriptions, liking, posting comments, and private playlists require OAuth 2.0 and additional implementation.
- API key is stored in app preferences for this prototype. Do not commit a real key to source control. For a production release, use an appropriate restricted-key strategy or backend.
- YouTube API Services Terms and embedded-player requirements apply.

## Phase 5 — channel pages
- Video search results now retain the YouTube `channelId` in local history/favorites JSON.
- Tapping the channel name on a video detail page opens a channel profile.
- Channel profile loads public channel title, avatar, description, subscriber count (when public), video count, and latest videos using `channels.list` and `search.list`.
- Selecting a channel video opens the existing embedded-player detail page.

### Phase 5 limitations
- Channel pages are read-only. Subscribe/unsubscribe still requires Google OAuth authorization and `subscriptions.insert` / `subscriptions.delete`.
- Subscriber count may be hidden by the channel owner.
- This source update has not been compiled or tested on a physical Android device in this environment.

## Channel profile UI update
The channel page now follows a Bilibili-style personal-space layout: top back/search/more actions, avatar and profile statistics, prominent channel name and description, profile section rows, and a two-column video grid. YouTube does not expose Bilibili-style following/likes/DM/dynamic/column data through the public channel endpoints, so unsupported metrics are shown as unavailable and the actions open/share the official YouTube channel rather than pretending to perform a native subscription or message.

## Channel-space search UI (Bilibili-inspired)
- The search icon on a channel page opens a dedicated full-screen search view.
- The search bar has back, clear, and submit controls and supports the keyboard Search action.
- Empty state prompts `搜索「频道名」的动态、视频`.
- Search results are restricted to the current YouTube channel via `search.list` with `channelId` and `q`.
- YouTube does not expose Bilibili-style channel dynamics, so this implementation searches channel videos only; it does not fabricate dynamic posts.

## Bilibili-style recommendation feed (stage update)
- Home remains a two-column Bilibili-inspired card feed.
- `推荐` and `热门` use YouTube Data API `videos.list(chart=mostPopular)` as a public popular-video discovery feed.
- `音乐`, `游戏`, and `科技` map to YouTube video categories (10, 20, 28).
- `动画` uses a relevance search for anime/animation because YouTube does not expose a dedicated anime category in the standard category list.
- Cards display public view counts where returned; pull-to-refresh is represented by the refresh icon.
- This is not YouTube's private/personalized home recommendation algorithm. The Data API does not expose the same personalized recommendation feed as the YouTube app.

## Recommendation cards — Bilibili-style polish
- Recommendation cards now use a consistent 16:9 thumbnail ratio and tighter two-column spacing.
- Thumbnail overlays include a play affordance and the video duration when returned by YouTube.
- Card metadata is arranged in a Bilibili-inspired hierarchy: two-line title, channel name, and compact play-count row.
- The public `videos.list` recommendation request now asks for `contentDetails` as well as `snippet` and `statistics`, then formats ISO-8601 durations as `m:ss` or `h:mm:ss`.
- These are presentation changes only; recommendation ranking still comes from YouTube's public most-popular chart, not Bilibili's algorithm or a personalized YouTube home feed.


## Recommendation interaction update
- Recommendation cards include a Bilibili-style overflow menu with an “不感兴趣” action.
- Hidden video IDs are persisted locally in SharedPreferences and excluded from future home-feed display.
- Feed results are deduplicated by YouTube video ID before display.
- “不感兴趣” is a local client-side filter; it does not send feedback to YouTube or change YouTube's own recommendation model.
- This update has not been compiled or tested on a physical Android device.


## Recommendation feed — refresh, pagination, and local affinity
- Added Material pull-to-refresh gesture to the home feed; refresh reloads the currently selected recommendation category.
- Added a `加载更多` footer when the YouTube `videos.list` response supplies a `nextPageToken` for the public popular chart.
- Added local, lightweight channel affinity ranking based on the user's recent watch history and saved favorites. It only reorders the public feed locally; it is not YouTube's private recommendation model and does not transmit viewing history.
- Hidden videos remain excluded and duplicate video IDs are removed when pages are appended.
- Animation and trending text-search tabs currently do not expose pagination in this implementation; load-more is available for the category-backed popular chart tabs.
- This update has not been compiled or tested on a physical Android device in this environment.

## Latest UI update
- Home category strip scrolls horizontally with a Bilibili-style active underline.
- Long-pressing a recommendation card opens a static preview dialog with thumbnail and metadata.
- Preview does not start playback. Playback begins only after the user taps the explicit “进入播放” action or taps the card normally.
- No autoplay preview is implemented.

## Latest home-feed layout refinement
- Replaced manually paired `LazyColumn` rows with a native Compose `LazyVerticalGrid` using two fixed columns, stable video-ID keys, and independent card sizing.
- The pagination footer spans the full grid width.
- This is a static thumbnail feed: there is no autoplay or inline video preview. Long-press opens the existing static metadata dialog; playback starts only after an explicit user action.
- Existing horizontal category navigation, refresh behavior, local hidden-video filtering, and public API pagination are retained.
- Build and device validation are still required in Android Studio; this source change has not been Gradle-compiled here.

## Latest feed scrolling update
- The two-column home grid now requests the next public YouTube API page automatically when the user scrolls near the end of the feed.
- A page-token guard prevents duplicate requests for the same page while the grid remains near its end.
- A compact loading indicator is shown while the next page is being fetched; an end-of-feed label appears when no further page token is returned.
- The explicit “加载更多” button has been removed to match the continuous-feed interaction more closely.
- No autoplay or inline playback preview has been added. Video playback still requires an explicit tap.
- This source update has not been Gradle-compiled or tested on a physical device in this environment.


## Video comments — continuous reading update
- The video detail page now requests the next public comment page when the reader scrolls close to the end of the currently loaded comments.
- A loading-state guard prevents overlapping comment-page requests; retry remains available after an API error.
- The manual “load more comments” button is replaced by a small scroll hint.
- Comment access remains read-only and depends on the video's public comment availability and the configured YouTube Data API key.
- This source update has not been Gradle-compiled or tested on a physical Android device in this environment.


## Video detail — creator profile card
- The video detail page now shows a creator card with the public channel avatar, channel title, subscriber count, and video count when returned by YouTube Data API `channels.list`.
- Tapping the card opens the existing channel-space page. If the channel lookup fails or the API key is not configured, the card falls back to the video result's channel title and does not invent statistics.
- This adds one `channels.list` lookup when opening a video detail page with a known channel ID; normal YouTube Data API quota limits apply.
- The card uses public YouTube fields only; subscribe/follow actions are not simulated.
- This source update has not been Gradle-compiled or tested on a physical Android device in this environment.


## Video detail action bar refinement
- Replaced plain text actions with larger touch targets and consistent Bilibili-inspired pill styling.
- Local favorites have a distinct selected state and are explicitly labeled as device-local storage.
- Share remains the Android share chooser for the YouTube video URL.
- Like, subscribe, and comment publishing are not presented as working actions; those require YouTube OAuth authorization and are not implemented in this build.
- Build and physical-device verification remain outstanding.


## Comment pagination stability fix
- Changed the comment-end observer from `collectLatest` to `collect`.
- The previous `collectLatest` observer could cancel an in-flight comment request when `commentsLoading` changed and emitted a new snapshot.
- The existing `commentsLoading` guard still prevents overlapping requests.
- This is a source-level correction; Android Gradle compilation and device testing remain pending.


## Compose API opt-in audit
- Added the explicit `ExperimentalMaterialApi` opt-in to `YouBiliApp`, which uses Material 2 pull-to-refresh APIs (`rememberPullRefreshState`, `pullRefresh`, and `PullRefreshIndicator`).
- This addresses the experimental-API compiler requirement for the selected Compose Material dependency.
- Static configuration review found matching Android Gradle Plugin 8.7.3 and Kotlin/Compose compiler plugin 2.0.21 declarations, with `namespace`, `compileSdk`, and INTERNET permission present.
- No Gradle wrapper is included and no Gradle executable is available in this environment; this audit has not been compiled. Other compiler or runtime issues may remain.


## Navigation and player lifecycle review

- Added Android system Back handling so video details, channel profiles, nested channel search, and non-home tabs unwind in the expected order.
- Added WebView release cleanup when the YouTube player leaves Compose composition to stop loading and destroy the embedded browser instance.
- Static source review only: this environment has no Gradle executable or Gradle Wrapper, so Android compilation and device playback remain unverified.
