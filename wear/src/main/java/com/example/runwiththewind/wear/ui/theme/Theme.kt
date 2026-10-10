package com.example.runwiththewind.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.MaterialTheme

val WarmBlack = Color(0xFF141110)
val SurfaceTile = Color(0xFF26201C)
val WarmTraySurface = Color(0xFF1E1915)
val BrandPrimary = Color(0xFFD97757)
val BrandPrimaryDim = Color(0xFFB85C3E)
val OnBrandPrimary = Color(0xFF1C1410)
val TextWarmWhite = Color(0xFFF2EDE6)
val TextMuted = Color(0xFFB8AEA3)
val SyncGreenSoft = Color(0xFF7FB77E)
val SyncPending = BrandPrimary
val BrandClay = BrandPrimary

@Composable
fun RunWithTheWindTheme(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme.copy(
        primary = BrandPrimary,
        primaryDim = BrandPrimaryDim,
        onPrimary = OnBrandPrimary,
        primaryContainer = BrandPrimaryDim,
        onPrimaryContainer = OnBrandPrimary,
        surfaceContainer = SurfaceTile,
        onSurface = TextWarmWhite,
        onSurfaceVariant = TextMuted,
        background = WarmBlack,
        onBackground = TextWarmWhite
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
