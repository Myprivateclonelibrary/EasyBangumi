package com.heyanle.easybangumi4.ui.sourcehome

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.with
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.heyanle.easy_i18n.R
import com.heyanle.easybangumi4.LocalNavController
import com.heyanle.easybangumi4.ui.common.PageContainer
import com.heyanle.easybangumi4.ui.common.page.CartoonPageUI

/**
 * Created by HeYanLe on 2023/2/25 17:32.
 * https://github.com/heyanLE
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceHome(key: String) {

    val nav = LocalNavController.current

    val vm = viewModel<SourceHomeViewModel>()

    //val topAppBarState = vm.topAppBarStateFlow.collectAsState()

    val state = vm.sourceHomeState
    val cur = vm.currentSourceState

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column(
                modifier = Modifier.background(MaterialTheme.colorScheme.background)
            ) {
                SourceHomeTopAppBar(
                    state = state,
                    onSearch = {
                        if (it.isEmpty() && cur !is SourceHomeViewModel.CurrentSourcePageState.Search) {
                            return@SourceHomeTopAppBar
                        }
                        vm.search(it)
                    },
                    onBack = {
                        nav.popBackStack()
                    }
                )
                state.let {
                    if(it is SourceHomeViewModel.SourceHomeState.Normal){
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(8.dp, 0.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(it.page) { page ->
                                vm.currentSourceState.let {
                                    val isSelect =
                                        it is SourceHomeViewModel.CurrentSourcePageState.Page && it.cartoonPage == page
                                    FilterChip(selected = isSelect, onClick = {
                                        vm.clickPage(page)
                                    }, label = { Text(page.label) })
                                }

                            }
                        }
                    }
                }
                Divider()

            }

        }
    ) { padding ->
        PageContainer(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
            sourceKey = key
        ) { bundle, sou, pages ->

            val search = remember {
                bundle.search(key)
            }

            LaunchedEffect(key1 = Unit) {
                vm.onInit(sou, pages, search)
            }

            Surface(
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground
            ) {
                vm.sourceHomeState.let {
                    if (it is SourceHomeViewModel.SourceHomeState.Normal) {
                        SourceHomeScreen(vm = vm, state = it)
                    }
                }
            }



        }
    }


}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceHomeTopAppBar(
    state: SourceHomeViewModel.SourceHomeState,
    onSearch: (String) -> Unit,
    onBack: () -> Unit,
) {

    val focusRequester = remember {
        FocusRequester()
    }

    var isCurrentSearchMode by remember {
        mutableStateOf(false)
    }

    var text by remember {
        mutableStateOf("")
    }

    LaunchedEffect(key1 = isCurrentSearchMode){
        if(isCurrentSearchMode == true){
            focusRequester.requestFocus()
        }
    }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = {
                if (isCurrentSearchMode) {
                    isCurrentSearchMode = false
                } else {
                    onBack()
                }
            }) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    stringResource(id = R.string.back)
                )
            }
        },
        title = {
            if (state is SourceHomeViewModel.SourceHomeState.Normal) {
                if (isCurrentSearchMode) {
                    TextField(
                        modifier = Modifier.focusRequester(focusRequester),
                        colors = TextFieldDefaults.textFieldColors(
                            containerColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                        ),
                        value = text,
                        onValueChange = {
                            text = it
                            if (it.isEmpty()) {
                                onSearch(it)
                            }
                        },
                        placeholder = {
                            Text(
                                style = MaterialTheme.typography.titleLarge,
                                text = stringResource(id = R.string.please_input_keyword_to_search)
                            )
                        }
                    )
                } else {
                    Text(text = state.source.label)
                }
            }
        },
        actions = {
            if (state is SourceHomeViewModel.SourceHomeState.Normal) {
                if (isCurrentSearchMode) {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = {
                            text = ""
                            onSearch("")
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                stringResource(id = com.heyanle.easy_i18n.R.string.clear)
                            )
                        }
                        IconButton(onClick = {
                            onSearch(text)
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                stringResource(id = com.heyanle.easy_i18n.R.string.search)
                            )
                        }
                    }

                }else if(state.search != null){
                    IconButton(onClick = {
                        isCurrentSearchMode = true
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            stringResource(id = com.heyanle.easy_i18n.R.string.search)
                        )
                    }
                }
            }
        }
    )

}

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SourceHomeScreen(
    vm: SourceHomeViewModel,
    state: SourceHomeViewModel.SourceHomeState.Normal
) {

    LaunchedEffect(key1 = Unit) {
        vm.currentSourceState.let {
            if (it is SourceHomeViewModel.CurrentSourcePageState.None) {
                state.page.find { !it.newScreen }?.let {
                    vm.clickPage(it)
                }
            }
        }
    }

    AnimatedContent(
        modifier = Modifier.fillMaxSize(),
        targetState = vm.currentSourceState,
        transitionSpec = {
            fadeIn(animationSpec = tween(300, delayMillis = 300)) with
                    fadeOut(animationSpec = tween(300, delayMillis = 0))
        }
    ) {
        when (it) {
            SourceHomeViewModel.CurrentSourcePageState.None -> {

            }

            is SourceHomeViewModel.CurrentSourcePageState.Page -> {
                CartoonPageUI(cartoonPage = it.cartoonPage)
            }

            is SourceHomeViewModel.CurrentSourcePageState.Search -> {

            }
        }
    }



}