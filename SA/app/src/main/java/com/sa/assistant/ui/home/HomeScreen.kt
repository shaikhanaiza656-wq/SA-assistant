package com.sa.assistant.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sa.assistant.ui.theme.SaAccentBlue
import com.sa.assistant.ui.theme.SaAccentCyan

/**
 * Landing tab. Shows the greeting + big mic button from the mockup.
 * Tapping the mic is wired to [HomeViewModel] which, in Phase 1, opens a
 * single voice turn over the socket (full wake-word listening lives in
 * [com.sa.assistant.core.assistant.AssistantForegroundService]).
 */
@Composable
fun HomeScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Good Morning, Boss!",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Main aapki kya madad kar sakta hoon?",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(48.dp))
        PulsingMicButton()
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Tap to Speak",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun PulsingMicButton() {
    val transition = rememberInfiniteTransition(label = "mic-pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic-scale"
    )

    Surface(
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
            .background(
                brush = Brush.radialGradient(listOf(SaAccentCyan, SaAccentBlue)),
                shape = CircleShape
            ),
        shape = CircleShape,
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Tap to speak",
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}
