package com.heyanle.easybangumi4.cartoon.repository

import com.heyanle.easybangumi4.base.DataResult
import com.heyanle.easybangumi4.base.map
import com.heyanle.easybangumi4.cartoon.entity.CartoonInfo
import com.heyanle.easybangumi4.cartoon.repository.db.dao.CartoonInfoDao
import com.heyanle.easybangumi4.getter.SourceStateGetter
import com.heyanle.easybangumi4.setting.SettingPreferences
import com.heyanle.easybangumi4.source_api.entity.PlayLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Created by HeYanLe on 2023/10/29 15:04.
 * https://github.com/heyanLE
 */
class CartoonRepository(
    private val settingPreferences: SettingPreferences,
    private val cartoonInfoDao: CartoonInfoDao,
    private val cartoonNetworkDataSource: CartoonNetworkDataSource,
    private val sourceStateGetter: SourceStateGetter
) {


    suspend fun awaitCartoonInfoWithPlayLines(
        id: String,
        source: String,
        url: String
    ): DataResult<Pair<CartoonInfo, List<PlayLine>>> {
        return withContext(Dispatchers.IO) {
            val local = cartoonInfoDao.getByCartoonSummary(id, source, url)
            val oldUpdateTime = local?.lastUpdateTime ?: 0L
            val current = System.currentTimeMillis()
            val expDiff = settingPreferences.cartoonInfoCacheTimeHour.get()
                .toDuration(DurationUnit.HOURS).inWholeMilliseconds
            if (local == null || local.getPlayLine()
                    .isEmpty() || current - oldUpdateTime >= expDiff
            ) {
                // 过期或者不存在 走网络
                val netResult = cartoonNetworkDataSource.awaitCartoonWithPlayLines(id, source, url)
                // 异步更新
                launch(Dispatchers.IO) {
                    if (netResult is DataResult.Ok) {
                        val sourceName =
                            sourceStateGetter.awaitBundle().source(source)?.label
                                ?: ""
                        val info = CartoonInfo.fromCartoon(
                            netResult.data.first,
                            sourceName,
                            netResult.data.second
                        )
                        cartoonInfoDao.modify(info)
                    }
                }


                val res = netResult.map {
                    val sourceName =
                        sourceStateGetter.awaitBundle().source(source)?.label ?: ""
                    CartoonInfo.fromCartoon(
                        it.first,
                        sourceName,
                        it.second
                    ) to it.second
                }
                // 如果网络错误，但是缓存还在，先返回缓存先用着
                if (res !is DataResult.Error || local == null) {
                    return@withContext res
                }
            }
            return@withContext DataResult.ok(local to local.getPlayLine())
        }
    }


}