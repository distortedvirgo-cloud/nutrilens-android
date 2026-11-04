package com.nutrilens.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nutrilens.app.data.Avatar
import com.nutrilens.app.ui.theme.NutriTheme

/** Своя аватарка (фото из Профиля) или буква ника на фирменном градиенте. */
@Composable
fun UserAvatar(nickname: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version by Avatar.version.collectAsStateWithLifecycle()
    val file = remember(version) { Avatar.file(context) }
    AvatarCircle(
        model = file?.let { ImageRequest.Builder(context).data(it).memoryCacheKey("avatar-$version-${it.lastModified()}").diskCacheKey("avatar-$version-${it.lastModified()}").build() },
        nickname = nickname, size = size, modifier = modifier
    )
}

/** Аватарка участника лидерборда из data-URL. */
@Composable
fun LeaderAvatar(avatar: String, nickname: String, size: Dp, modifier: Modifier = Modifier) {
    val bytes = remember(avatar) { Avatar.decodeDataUrl(avatar) }
    AvatarCircle(model = bytes, nickname = nickname, size = size, modifier = modifier)
}

@Composable
private fun AvatarCircle(model: Any?, nickname: String, size: Dp, modifier: Modifier) {
    Box(
        modifier.size(size).clip(CircleShape)
            .background(Brush.linearGradient(listOf(NutriTheme.colors.brandFrom, MaterialTheme.colorScheme.secondary))),
        contentAlignment = Alignment.Center
    ) {
        val initial = nickname.trim().firstOrNull()?.uppercase()
        if (initial != null) Text(initial, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.ExtraBold, color = NutriTheme.colors.onBrand)
        else Icon(Icons.Rounded.Person, contentDescription = null, tint = NutriTheme.colors.onBrand)
        if (model != null) AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
