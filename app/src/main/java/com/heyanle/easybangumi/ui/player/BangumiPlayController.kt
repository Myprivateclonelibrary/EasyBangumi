package com.heyanle.easybangumi.ui.player

import android.util.Log
import androidx.collection.LruCache
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.MutableLiveData
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.heyanle.easybangumi.BangumiApp
import com.heyanle.easybangumi.player.PlayerController
import com.heyanle.easybangumi.player.PlayerTinyController
import com.heyanle.easybangumi.ui.common.easy_player.EasyPlayerView
import com.heyanle.easybangumi.utils.toast
import com.heyanle.eplayer_core.constant.EasyPlayStatus
import com.heyanle.lib_anim.entity.BangumiSummary
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Created by HeYanLe on 2023/1/15 19:53.
 * https://github.com/heyanLE
 */
object BangumiPlayController {

    private var lastScope = MainScope()

    val lastPauseLevel = mutableStateOf(0)
    private var composeViewRes: WeakReference<EasyPlayerView>? = null

    private val animPlayViewModelCache: LruCache<BangumiSummary, AnimPlayViewModel> = LruCache(3)

    val curAnimPlayViewModel = MutableLiveData<AnimPlayViewModel>()

    val curPlayerStatus = MutableLiveData<AnimPlayViewModel.PlayerStatus>()

    fun newBangumi(bangumiSummary: BangumiSummary){
        val new = getAnimPlayViewModel(bangumiSummary)
        if(new != curAnimPlayViewModel.value){
            curAnimPlayViewModel.postValue(new)
        }

    }

    fun getAnimPlayViewModel(bangumiSummary: BangumiSummary): AnimPlayViewModel{
        val cache = animPlayViewModelCache[bangumiSummary]
        if(cache == null){
            val n = AnimPlayViewModel(bangumiSummary)
            animPlayViewModelCache.put(bangumiSummary, n)
            return n
        }
        return cache
    }

    fun onNewComposeView(easyPlayerView: EasyPlayerView){
        this.composeViewRes = WeakReference(easyPlayerView)
    }

    // activity 的 onPause 调用
    fun onPause(){
        lastPauseLevel.value = lastPauseLevel.value+1
    }

    fun onPlayerScreenReshow(){
        composeViewRes?.get()?.basePlayerView?.attachToPlayer(PlayerController.exoPlayer)
    }

    var lastPlayerStatus: AnimPlayViewModel.PlayerStatus.Play? = null

    init {
        curAnimPlayViewModel.observeForever {
            lastScope.cancel()
            val scope = MainScope()
            lastScope = scope
            scope.launch {
                it.playerStatus.collectLatest {
                    curPlayerStatus.postValue(it)
                }
            }
        }
        curPlayerStatus.observeForever {
            when(it){
                is AnimPlayViewModel.PlayerStatus.Loading -> {
                    if(PlayerTinyController.isTinyMode) {
                        PlayerTinyController.tinyPlayerView.basePlayerView.dispatchPlayStateChange(
                            EasyPlayStatus.STATE_PREPARING
                        )
                    }
                }
                is AnimPlayViewModel.PlayerStatus.Play -> {
                    Log.d("BangumiPlayController", it.uri)
                    if(PlayerTinyController.isTinyMode) {
                        PlayerTinyController.tinyPlayerView.basePlayerView.refreshStateOnce()
                    }
                    if(lastPlayerStatus?.uri != it.uri || lastPlayerStatus?.type != it.type){
                        val defaultDataSourceFactory = DefaultDataSource.Factory(BangumiApp.INSTANCE)
                        val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(
                            BangumiApp.INSTANCE,
                            defaultDataSourceFactory
                        )
                        val media = when (it.type) {
                            C.CONTENT_TYPE_DASH -> DashMediaSource.Factory(dataSourceFactory)
                                .createMediaSource(MediaItem.fromUri(it.uri))
                            C.CONTENT_TYPE_HLS -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(
                                MediaItem.fromUri(it.uri)
                            )
                            else -> ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(
                                MediaItem.fromUri(it.uri)
                            )
                        }
                        PlayerController.exoPlayer.setMediaSource(media)
                        PlayerController.exoPlayer.prepare()
                    }
                    lastPlayerStatus = it
                }
                is AnimPlayViewModel.PlayerStatus.Error -> {
                    if(PlayerTinyController.isTinyMode){
                        it.errorMsg.toast()
                        PlayerTinyController.dismissTiny()
                        PlayerController.exoPlayer.stop()
                    }

                }
                else -> {

                }
            }
        }
    }

}