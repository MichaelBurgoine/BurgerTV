package com.example.tvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** Light tile behind logos so dark and transparent logos stay readable. */
val LogoTile = Color(0xFFE6EBF2)
private val LogoInk = Color(0xFF4A5670)

fun initialsOf(name: String) =
    name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }

@Composable
fun ChannelLogo(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    background: Color = LogoTile,
    fallbackName: String? = null,
) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(8))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null && fallbackName != null) {
            Text(initialsOf(fallbackName), style = MaterialTheme.typography.titleMedium, color = LogoInk)
        } else {
            AsyncImage(
                // Decode at display size only: full-size logos are the main memory hog on sticks.
                model = ImageRequest.Builder(LocalContext.current).data(url).size(px).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size),
            )
        }
    }
}
