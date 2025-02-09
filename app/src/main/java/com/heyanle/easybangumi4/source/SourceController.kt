package com.heyanle.easybangumi4.source

import com.heyanle.easybangumi4.cartoon.repository.db.dao.CartoonStarDao
import com.heyanle.easybangumi4.cartoon.entity.CartoonStar
import com.heyanle.easybangumi4.extension.Extension
import com.heyanle.easybangumi4.getter.ExtensionGetter
import com.heyanle.easybangumi4.source.bundle.ComponentBundle
import com.heyanle.easybangumi4.source.bundle.SourceBundle
import com.heyanle.easybangumi4.source_api.MigrateSource
import com.heyanle.easybangumi4.source_api.Source
import com.heyanle.easybangumi4.source_api.SourceResult
import com.heyanle.easybangumi4.source_api.component.detailed.DetailedComponent
import com.heyanle.easybangumi4.source_api.component.preference.PreferenceComponent
import com.heyanle.easybangumi4.source_api.entity.Cartoon
import com.heyanle.easybangumi4.source_api.entity.PlayLine
import com.heyanle.easybangumi4.source_api.entity.toIdentify
import com.heyanle.easybangumi4.source_api.utils.api.PreferenceHelper
import com.heyanle.easybangumi4.utils.TimeLogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Created by heyanlin on 2023/10/27.
 */
class SourceController(
    private val extensionGetter: ExtensionGetter,
    private val sourcePreferences: SourcePreferences,
    private val cartoonStarDao: CartoonStarDao,
) {

    sealed class SourceInfoState {
        data object Loading : SourceInfoState()

        class Info(val info: List<SourceInfo>) : SourceInfoState()
    }

    private val _sourceInfo = MutableStateFlow<SourceInfoState>(SourceInfoState.Loading)
    val sourceInfo = _sourceInfo.asStateFlow()

    private val _configSource = MutableStateFlow<List<ConfigSource>>(emptyList())
    val configSource = _configSource.asStateFlow()

    private val _sourceBundle = MutableStateFlow<SourceBundle?>(null)
    val sourceBundle = _sourceBundle.asStateFlow()


    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val migrateScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val scope = MainScope()

    init {
        scope.launch {
            extensionGetter.flowExtensionState().collectLatest { sta ->
                if(sta.isLoading){
                    _sourceInfo.update {
                        SourceInfoState.Loading
                    }
                }else{
                    TimeLogUtils.i("loadSource start")
                    val it = sta.appExtensions.values + sta.fileExtension.values
                    val map = hashMapOf<String, Source>()
                    it.filterIsInstance<Extension.Installed>().flatMap {
                        it.sources
                    }.forEach {
                        val old = map[it.key]
                        if (old == null || old.versionCode <= it.versionCode) {
                            map[it.key] = it
                        }
                    }
                    val n = map.values.map {
                        loadSource(it)
                    }
                    _sourceInfo.update {
                        SourceInfoState.Info(n)
                    }
                }
            }
        }
        scope.launch {
            _sourceInfo.filterIsInstance<SourceInfoState.Info>()
                .map { it.info.filterIsInstance<SourceInfo.Migrating>() }
                .collectLatest {
                    migrateScope.launch {
                        it.forEach {
                            migrate(it)
                        }
                    }
                }
        }
        scope.launch {
            combine(
                _sourceInfo.filterIsInstance<SourceInfoState.Info>().map { it.info },
                sourcePreferences.configs.stateIn(scope)
            ) { sourceInfo, config ->
                val d = sourceInfo.map {
                    val con =
                        config[it.source.key] ?: SourceConfig(it.source.key, Int.MAX_VALUE, true)
                    ConfigSource(it, con)
                }
                d
            }.collectLatest { list ->
                _configSource.update {
                    list
                }
            }
        }

        scope.launch {
            combine(
                _sourceInfo,
                _configSource,
            ){sta, config ->
                if(sta is SourceInfoState.Info){
                    _sourceBundle.update {
                        SourceBundle(config)
                    }
                }
            }.collect()
        }

    }

    private fun loadSource(source: Source): SourceInfo {
        TimeLogUtils.i("loadSource ${source.key} start")
        return try {
            val bundle = ComponentBundle(source)
            bundle.init()
            if (needMigrate(source, bundle)) {
                SourceInfo.Migrating(source, bundle)
            } else {
                SourceInfo.Loaded(source, bundle)
            }
        } catch (e: SourceException) {
            SourceInfo.Error(source, e.msg, e)
        } catch (e: Exception) {
            e.printStackTrace()
            SourceInfo.Error(source, "加载错误：${e.message}", e)
        }
    }


    private fun needMigrate(source: Source, componentBundle: ComponentBundle): Boolean {
        val old = sourcePreferences.getLastVersion(source)
        if (source is MigrateSource && source.needMigrate(old.get())) {
            return true
        }
        val preferences = componentBundle.get<PreferenceComponent>()
        if (preferences != null && preferences.needMigrate(old.get())) {
            return true
        }
        return false
    }

    private suspend fun migrate(sourceInfo: SourceInfo.Migrating) {
        val old = sourcePreferences.getLastVersion(sourceInfo.source).get()
        val preferences = sourceInfo.componentBundle.get<PreferenceComponent>()
        val preferenceHelper = sourceInfo.componentBundle.get<PreferenceHelper>()
        if (preferences != null && preferenceHelper != null) {
            if (preferences.needMigrate(old)) {
                preferences.onMigrate(preferenceHelper.map(), old).forEach {
                    preferenceHelper.put(it.key, it.value)
                }
            }
        }
        val so = sourceInfo.source
        if (so is MigrateSource) {
            if (so.needMigrate(old)) {
                val oldStart = cartoonStarDao.getAllBySource(so.key)
                val detailed = sourceInfo.componentBundle.get<DetailedComponent>()
                if (detailed != null) {
                    val news = so.onMigrate(oldStart.map { it.getSummary() }, old).map {
                        detailed.getAll(it)
                    }.filterIsInstance<SourceResult.Complete<Pair<Cartoon, List<PlayLine>>>>()
                        .map { co ->
                            CartoonStar.fromCartoon(
                                co.data.first,
                                sourceInfo.source.label,
                                co.data.second,
                                oldStart.find { it.toIdentify() == co.data.first.toIdentify() }?.tags
                                    ?: ""
                            )
                        }
                    cartoonStarDao.migration(oldStart, news)
                }

            }
        }
        _sourceInfo.update { sourceInfos ->
            if (sourceInfos is SourceInfoState.Info) {
                SourceInfoState.Info(sourceInfos.info.map {
                    if (it.source.key == sourceInfo.source.key && it is SourceInfo.Migrating) {
                        SourceInfo.Loaded(it.source, it.componentBundle)
                    } else {
                        it
                    }
                })
            } else {
                sourceInfos
            }

        }

    }


}