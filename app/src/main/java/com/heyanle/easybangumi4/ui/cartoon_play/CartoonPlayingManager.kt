package com.heyanle.easybangumi4.ui.cartoon_play

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.analytics.DefaultAnalyticsCollector
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.util.Clock
import com.heyanle.bangumi_source_api.api.component.play.PlayComponent
import com.heyanle.bangumi_source_api.api.entity.Cartoon
import com.heyanle.bangumi_source_api.api.entity.CartoonSummary
import com.heyanle.bangumi_source_api.api.entity.PlayLine
import com.heyanle.bangumi_source_api.api.entity.PlayerInfo
import com.heyanle.easybangumi4.APP
import com.heyanle.easybangumi4.DB
import com.heyanle.easybangumi4.db.entity.CartoonHistory
import com.heyanle.easybangumi4.source.SourceMaster
import com.heyanle.easybangumi4.utils.stringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Created by HeYanLe on 2023/3/7 14:45.
 * https://github.com/heyanLE
 */
object CartoonPlayingManager: Player.Listener {

    val defaultScope = MainScope()


    sealed class PlayingState {
        object None : PlayingState()

        class Loading(
            val playLineIndex: Int,
            val playLine: PlayLine,
            val curEpisode: Int,
        ) : PlayingState()

        class Playing(
            val playLineIndex: Int,
            val playerInfo: PlayerInfo,
            val playLine: PlayLine,
            val curEpisode: Int,
        ) : PlayingState()

        class Error(
            val playLineIndex: Int,
            val errMsg: String,
            val throwable: Throwable?,
            val playLine: PlayLine,
            val curEpisode: Int,
        ) : PlayingState()

        fun playLine(): PlayLine? {
            return when (this) {
                None -> null
                is Loading -> playLine
                is Playing -> playLine
                is Error -> playLine
            }
        }

        fun playLineIndex(): Int? {
            return when (this) {
                None -> null
                is Loading -> playLineIndex
                is Playing -> playLineIndex
                is Error -> playLineIndex
            }
        }

        fun episode(): Int {
            return when (this) {
                None -> -1
                is Loading -> curEpisode
                is Playing -> curEpisode
                is Error -> curEpisode
            }
        }
    }

    var state by mutableStateOf<PlayingState>(PlayingState.None)


    private var lastPlayerInfo: PlayerInfo? = null

    private var playComponent: PlayComponent? = null
    private var cartoon: Cartoon? = null

    private var saveLoopJob: Job? = null


    val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(
            APP,
            DefaultRenderersFactory(APP),
            DefaultMediaSourceFactory(APP),
            DefaultTrackSelector(APP),
            DefaultLoadControl(),
            DefaultBandwidthMeter.getSingletonInstance(APP),
            DefaultAnalyticsCollector(Clock.DEFAULT)
        ).build().apply {
            addListener(CartoonPlayingManager)
        }
    }

    suspend fun refresh() {
        val cartoonSummary = cartoon ?: return
        val playComponent = playComponent ?: return
        val playLine = state.playLine() ?: return
        val playIndex = state.playLineIndex() ?: return
        changePlay(playComponent, cartoonSummary, playIndex, playLine, state.episode(), 0)
    }

    suspend fun changeLine(
        sourceKey: String,
        cartoon: Cartoon,
        playLineIndex: Int,
        playLine: PlayLine,
        defaultEpisode: Int = 0,
        defaultProgress: Long = 0L,
    ) {
        val playComponent = SourceMaster.animSourceFlow.value.play(sourceKey) ?: return
        CartoonPlayingManager.playComponent = playComponent
        CartoonPlayingManager.cartoon = cartoon
        changePlay(playComponent, cartoon, playLineIndex, playLine, defaultEpisode, defaultProgress)
    }

    suspend fun tryNext(
        defaultProgress: Long = 0L,
    ): Boolean {
        val playingState = (state as? PlayingState.Playing) ?: return false
        val target = playingState.curEpisode + 1
        if (target < 0 || target >= playingState.playLine.episode.size) {
            return false
        }
        changeEpisode(playingState.curEpisode + 1, defaultProgress)
        return true
    }

    suspend fun changeEpisode(
        episode: Int,
        defaultProgress: Long = 0L,
    ): Boolean {
        val cartoonSummary = cartoon ?: return false
        val playComponent = playComponent ?: return false
        val playingState = (state as? PlayingState.Playing) ?: return false
        changePlay(
            playComponent,
            cartoonSummary,
            playingState.playLineIndex,
            playingState.playLine,
            episode,
            defaultProgress
        )
        return true
    }

    private suspend fun changePlay(
        playComponent: PlayComponent,
        cartoon: Cartoon,
        playLineIndex: Int,
        playLine: PlayLine,
        episode: Int = 0,
        adviceProgress: Long = 0L,
    ) {
        if (playLine.episode.isEmpty()) {
            return
        }
        val realEpisode = if (episode < 0 || episode >= playLine.episode.size) 0 else episode

        state = PlayingState.Loading(playLineIndex, playLine, realEpisode)
        playComponent.getPlayInfo(
            CartoonSummary(cartoon.id, cartoon.url, cartoon.source),
            playLine,
            episode
        )
            .complete {
                innerPlay(it.data, adviceProgress)
                state = PlayingState.Playing(
                    playLineIndex, it.data, playLine, episode,
                )
            }
            .error {
                error(

                    if (it.isParserError) stringRes(
                        com.heyanle.easy_i18n.R.string.source_error
                    ) else stringRes(com.heyanle.easy_i18n.R.string.loading_error),
                    it.throwable, playLineIndex, playLine, episode
                )
            }

    }

    private suspend fun innerTrySaveHistory(ps: Long = -1){
        var process = ps
        if (ps == -1L) {
            process = exoPlayer.currentPosition
        }
        if (process == -1L) {
            process = 0L
        }
        // 只有在播放状态才更新历史
        val playingState = state as? PlayingState.Playing ?: return
        val curCartoon = cartoon ?: return

        val history = CartoonHistory(
            id = curCartoon.id,
            source = curCartoon.source,
            url = curCartoon.url,

            cover = curCartoon.coverUrl ?: "",
            name = curCartoon.title,
            intro = curCartoon.intro ?: "",

            lastLinesIndex = playingState.playLineIndex,
            lastLineTitle = playingState.playLine.label,
            lastEpisodeIndex = playingState.curEpisode,
            lastEpisodeTitle = playingState.playLine.episode[playingState.curEpisode],
            lastProcessTime = process,

            createTime = System.currentTimeMillis()
        )
        DB.cartoonHistory.insertOrModify(history)
    }

    fun trySaveHistory(ps: Long = -1) {
        var process = ps
        if (ps == -1L) {
            process = exoPlayer.currentPosition
        }
        if (process == -1L) {
            process = 0L
        }
        // 只有在播放状态才更新历史
        val playingState = state as? PlayingState.Playing ?: return
        val curCartoon = cartoon ?: return

        defaultScope.launch(Dispatchers.IO) {
            val history = CartoonHistory(
                id = curCartoon.id,
                source = curCartoon.source,
                url = curCartoon.url,

                cover = curCartoon.coverUrl ?: "",
                name = curCartoon.title,
                intro = curCartoon.intro ?: "",

                lastLinesIndex = playingState.playLineIndex,
                lastLineTitle = playingState.playLine.label,
                lastEpisodeIndex = playingState.curEpisode,
                lastEpisodeTitle = playingState.playLine.episode[playingState.curEpisode],
                lastProcessTime = process,

                createTime = System.currentTimeMillis()
            )
            DB.cartoonHistory.insertOrModify(history)
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        super.onPlayWhenReadyChanged(playWhenReady, reason)
        if(!playWhenReady && exoPlayer.isMedia()){
            defaultScope.launch (Dispatchers.IO) {
                innerTrySaveHistory()
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        super.onIsPlayingChanged(isPlaying)
        if(isPlaying){
            if(saveLoopJob == null || saveLoopJob?.isActive != true){
                saveLoopJob = defaultScope.launch(Dispatchers.IO) {
                    innerTrySaveHistory()
                }
            }
        }else{
            saveLoopJob?.cancel()
            saveLoopJob = null
        }
    }

    private fun innerPlay(playerInfo: PlayerInfo, adviceProgress: Long = 0L) {

        trySaveHistory(adviceProgress)

        // 如果播放器当前状态不在播放，则肯定要刷新播放源
        if (!exoPlayer.isMedia() || lastPlayerInfo?.uri != playerInfo.uri || lastPlayerInfo?.decodeType != playerInfo.decodeType) {
            val defaultDataSourceFactory =
                DefaultDataSource.Factory(APP)
            val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(
                APP,
                defaultDataSourceFactory
            )
            val media = when (playerInfo.decodeType) {
                C.CONTENT_TYPE_DASH -> DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(playerInfo.uri))

                C.CONTENT_TYPE_HLS -> HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(
                        MediaItem.fromUri(playerInfo.uri)
                    )

                else -> ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(
                        MediaItem.fromUri(playerInfo.uri)
                    )
            }

            exoPlayer.setMediaSource(media, adviceProgress)
            exoPlayer.prepare()
        } else {
            // 已经在播放同一部，直接 seekTo 对应 progress
            exoPlayer.seekTo(adviceProgress)
        }
    }

    private fun error(
        errMsg: String,
        throwable: Throwable? = null,
        playLineIndex: Int,
        playLine: PlayLine,
        episode: Int,
    ) {
        state = PlayingState.Error(playLineIndex, errMsg, throwable, playLine, episode)
    }


    private fun ExoPlayer.isMedia(): Boolean {
        return exoPlayer.playbackState == Player.STATE_BUFFERING || exoPlayer.playbackState == Player.STATE_READY
    }

}