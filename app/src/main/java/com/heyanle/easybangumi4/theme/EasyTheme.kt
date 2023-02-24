package com.heyanle.easybangumi4.theme

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.with
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.LocalElevationOverlay
import androidx.compose.material.ripple.LocalRippleTheme
import androidx.compose.material.ripple.RippleTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.google.accompanist.systemuicontroller.rememberSystemUiController

/**
 * Created by HeYanLe on 2023/2/19 0:02.
 * https://github.com/heyanLE
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun EasyTheme(
    content: @Composable () -> Unit
) {

    val easyThemeState by EasyThemeController.easyThemeState
    AnimatedContent(
        targetState = easyThemeState,
        transitionSpec = {
            fadeIn(animationSpec = tween(300, delayMillis = 0)) with
                    fadeOut(animationSpec = tween(300, delayMillis = 300))
        },
    ) {
        val isDynamic = it.isDynamicColor && EasyThemeController.isSupportDynamicColor()
        val isDark = when (it.darkMode) {
            DarkMode.Dark -> true
            DarkMode.Light -> false
            else -> isSystemInDarkTheme()
        }

        val colorScheme = when {
            isDynamic -> {
                val context = LocalContext.current
                if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            }

            else -> {
                Log.d("EasyTheme", it.themeMode.name)
                it.themeMode.getColorScheme(isDark)
            }
        }

        val uiController = rememberSystemUiController()
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                uiController.setStatusBarColor(Color.Transparent, !isDark)
                uiController.setNavigationBarColor(Color.Transparent, !isDark)
            }
        }

        LaunchedEffect(key1 = colorScheme) {
            EasyThemeController.curThemeColor = colorScheme
        }

        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }


}