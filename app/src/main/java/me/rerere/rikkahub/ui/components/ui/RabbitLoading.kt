package me.rerere.rikkahub.ui.components.ui

import android.graphics.drawable.AnimatedVectorDrawable
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalSettings

/**
 * Chat loading indicator. Phase 21: draws the Omnitrix dial (omnitrix_loading, converted
 * from loading_icon.svg and sized to the drawable it replaced) tinted with the theme
 * primary. Falls back to the Material indicator when the app-icon style is switched off.
 */
@Composable
fun RabbitLoadingIndicator(modifier: Modifier = Modifier) {
    val useAppIconStyleLoadingIndicator = LocalSettings.current.displaySetting.useAppIconStyleLoadingIndicator
    val primaryColor = MaterialTheme.colorScheme.primary.toArgb()

    if (useAppIconStyleLoadingIndicator) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                ImageView(context).apply {
                    val drawable = AppCompatResources.getDrawable(context, R.drawable.omnitrix_loading) as? AnimatedVectorDrawable
                    setImageDrawable(drawable)
                    drawable?.setTint(primaryColor)
                    drawable?.start()
                }
            },
            update = { imageView ->
                (imageView.drawable as? AnimatedVectorDrawable)?.apply {
                    setTint(primaryColor)
                    start()
                }
            }
        )
    } else {
        ContainedLoadingIndicator(
            modifier = modifier,
        )
    }
}
