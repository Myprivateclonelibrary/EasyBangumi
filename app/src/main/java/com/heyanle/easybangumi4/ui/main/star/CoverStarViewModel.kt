package com.heyanle.easybangumi4.ui.main.star

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heyanle.easy_i18n.R
import com.heyanle.easybangumi4.cartoon.repository.db.dao.CartoonStarDao
import com.heyanle.easybangumi4.cartoon.entity.CartoonStar
import com.heyanle.easybangumi4.getter.CartoonInfoGetter
import com.heyanle.easybangumi4.source_api.entity.CartoonCover
import com.heyanle.easybangumi4.source_api.entity.toIdentify
import com.heyanle.easybangumi4.ui.common.moeSnackBar
import com.heyanle.easybangumi4.utils.stringRes
import com.heyanle.injekt.core.Injekt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * CartoonCover 的 star 逻辑抽取
 * Created by heyanlin on 2023/8/4.
 * https://github.com/heyanLE
 */
class CoverStarViewModel : ViewModel() {

    private val cartoonStarDao: CartoonStarDao by Injekt.injectLazy()
    val starFlow =
        cartoonStarDao.flowAll()
            .map { stars ->
                stars.map {
                    it.toIdentify()
                }.toSet()
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    val starState = mutableStateOf<Set<String>>(starFlow.value)

    // 因为收藏番剧需要拉取番剧所有数据，有耗时，因此这里触发收藏后先临时展示收藏完成
    // 当开始收藏任务后先加到该临时列表，收藏失败或者成功都移除
    // 该列表中的番剧在展示上视为已收藏
    private val staringCartoon = mutableStateMapOf<String, Boolean>()

    private val cartoonInfoGetter: CartoonInfoGetter by Injekt.injectLazy()

    init {
        viewModelScope.launch {
            starFlow.collectLatest {
                starState.value = it
            }
        }
    }

    fun star(cartoonCover: CartoonCover) {
        viewModelScope.launch {
            val identify = cartoonCover.toIdentify()
            val isStar = starFlow.value.contains(identify)
            if (isStar || staringCartoon.contains(identify)) {
                // 临时列表和数据库都删了
                // 因为是主线程不用考虑并发问题
                staringCartoon.remove(identify)
                cartoonStarDao.deleteByCartoonSummary(
                    cartoonCover.id,
                    cartoonCover.source,
                    cartoonCover.url
                )
            } else {
                staringCartoon[identify] = true

                cartoonInfoGetter.awaitCartoonInfoWithPlayLines(
                    cartoonCover.id,
                    cartoonCover.source,
                    cartoonCover.url
                )
                    .onOK {
                        cartoonStarDao.modify(
                            CartoonStar.fromCartoonInfo(it.first, it.second).apply {
                                reversal = false
                            })
                    }.onError {
                        it.throwable?.printStackTrace()
                        if (isActive) {
                            staringCartoon.remove(identify)
                            (stringRes(R.string.detailed_error) + it.throwable?.message).moeSnackBar()
                        }
                    }
            }
        }
    }

    fun isCoverStarted(cartoonCover: CartoonCover): Boolean {
        return staringCartoon[cartoonCover.toIdentify()] == true || starState.value.contains(
            cartoonCover.toIdentify()
        )
    }
}