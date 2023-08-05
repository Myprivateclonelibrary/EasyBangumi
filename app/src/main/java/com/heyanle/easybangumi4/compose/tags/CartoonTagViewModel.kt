package com.heyanle.easybangumi4.compose.tags

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.heyanle.easybangumi4.base.db.dao.CartoonTagDao
import com.heyanle.easybangumi4.base.entity.CartoonTag
import com.heyanle.injekt.core.Injekt
import kotlinx.coroutines.launch

/**
 * Created by HeYanLe on 2023/8/6 16:51.
 * https://github.com/heyanLE
 */
class CartoonTagViewModel : ViewModel() {

    var dialog by mutableStateOf<Dialog?>(null)
        private set
    var tags by mutableStateOf<List<CartoonTag>>(emptyList())
        private set

    private val cartoonTagDao: CartoonTagDao by Injekt.injectLazy()

    var state by mutableStateOf(State(true, emptyList(), emptySet(), null))

    data class State(
        val isLoading: Boolean = false, // 只有初始状态会为 true
        val tags: List<CartoonTag>, // 排序中会和数据库里的不同,
        val selection: Set<CartoonTag>,
        val dialog: Dialog? = null,
    )

    sealed class Dialog {

        class Delete(
            val deleteTag: CartoonTag
        ) : Dialog()

        class DeleteSelection(
            val selection: List<CartoonTag>
        ) : Dialog()

        class Rename(
            val renameTag: CartoonTag
        ) : Dialog()

        object Create : Dialog()
    }

    init {
        viewModelScope.launch {
            cartoonTagDao.flowAll().collect {
                tags = it.sortedBy { it.order }
            }
        }
    }

    fun move(from: Int, to: Int) {
        tags = tags.toMutableList().apply {
            add(to, removeAt(from))
        }
    }

    fun onDragEnd() {
        viewModelScope.launch {
            val ts = tags.mapIndexed { index, cartoonTag ->
                cartoonTag.copy(order = index)
            }
            cartoonTagDao.updateAll(ts)
        }
    }

    fun dialogDelete(cartoonTag: CartoonTag) {
        dialog = Dialog.Delete(cartoonTag)
    }

    fun dialogRename(cartoonTag: CartoonTag) {
        dialog = Dialog.Rename(cartoonTag)
    }

    fun dialogCreate() {
        dialog = Dialog.Create
    }


    fun dialogDismiss() {
        dialog = null
    }

    fun onDelete(cartoonTag: CartoonTag) {
        viewModelScope.launch {
            cartoonTagDao.delete(cartoonTag)

        }
    }

    fun onRename(cartoonTag: CartoonTag, label: String) {
        viewModelScope.launch {
            cartoonTagDao.update(
                cartoonTag.copy(
                    label = label
                )
            )
        }
    }

    fun onCreate(label: String) {
        viewModelScope.launch {
            cartoonTagDao.insert(
                CartoonTag(0, label, tags.size + 1)
            )
        }
    }

}