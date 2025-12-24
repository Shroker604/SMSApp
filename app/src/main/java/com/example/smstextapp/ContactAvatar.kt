package com.example.smstextapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.absoluteValue

@Composable
fun ContactAvatar(
    displayName: String,
    photoUri: String?,
    size: Dp = 48.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (photoUri == null) getAvatarColor(displayName) else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        if (photoUri != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(photoUri)
                    .crossfade(true)
                    .build(),
                contentDescription = "Contact Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Monogram
            val initial = displayName.firstOrNull()?.toString()?.uppercase() ?: "?"
            Text(
                text = initial,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

// Generate a consistent pastel-like color from the name string
fun getAvatarColor(name: String): Color {
    val hash = name.hashCode().absoluteValue
    // We can use a set of Material colors or generate hue
    val hue = (hash % 360).toFloat()
    return Color.hsv(hue, 0.6f, 0.8f) // Saturation 0.6, Value 0.8 for nice pastel
}
