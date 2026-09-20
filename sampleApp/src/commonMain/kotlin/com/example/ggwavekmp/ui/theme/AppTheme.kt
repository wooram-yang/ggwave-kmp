package com.example.ggwavekmp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.materialkolor.rememberDynamicColorScheme

@Composable
fun AppTheme(
    seedColor: Color = Color.Cyan,
    useDarkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = rememberDynamicColorScheme(seedColor, useDarkTheme, false)

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
