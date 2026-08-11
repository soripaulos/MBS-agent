package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.OmnitrixVariant
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import kotlin.math.cos
import kotlin.math.sin

/**
 * Phase 19 — Omnitrix-style assistant selector: assistants sit around a dial whose center
 * shows the selected Omnitrix variant art ([OmnitrixVariant], placeholder drawables the
 * user can replace — see docs/omnitrix-assets.md). Tapping an avatar rotates the dial to
 * it; "Transform" confirms the switch. Toggleable back to the plain list sheet via
 * DisplaySetting.useOmnitrixSelector.
 */
fun OmnitrixVariant.dialDrawableRes(): Int = when (this) {
    OmnitrixVariant.ORIGINAL -> R.drawable.omnitrix_original
    OmnitrixVariant.ALIEN_FORCE -> R.drawable.omnitrix_alien_force
    OmnitrixVariant.ULTIMATRIX -> R.drawable.omnitrix_ultimatrix
    OmnitrixVariant.OMNIVERSE -> R.drawable.omnitrix_omniverse
}

@Composable
fun OmnitrixSelectorSheet(
    settings: Settings,
    currentAssistant: Assistant,
    onAssistantSelected: (Assistant) -> Unit,
    onDismiss: () -> Unit,
) {
    val assistants = settings.assistants
    if (assistants.isEmpty()) {
        onDismiss()
        return
    }
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    val variant = settings.displaySetting.omnitrixVariant
    var selectedIndex by remember {
        mutableIntStateOf(assistants.indexOfFirst { it.id == currentAssistant.id }.coerceAtLeast(0))
    }
    val selected = assistants[selectedIndex]
    val navController = LocalNavController.current

    // The dial rotates so the selected assistant's slot lands on top.
    val anglePerSlot = 360f / assistants.size
    val dialRotation by animateFloatAsState(
        targetValue = -selectedIndex * anglePerSlot,
        animationSpec = tween(durationMillis = 350),
        label = "dialRotation",
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.omnitrix_selector_title),
                style = MaterialTheme.typography.titleLarge,
            )

            // The dial: center art + avatars on a ring that rotates with the selection.
            val dialSize = 260.dp
            val ringRadius = 105.dp
            Box(
                modifier = Modifier.size(dialSize),
                contentAlignment = Alignment.Center,
            ) {
                // Phase 21 — the dial itself IS the confirm button: tap the Omnitrix core
                // to transform into the highlighted assistant. Press feedback scales the
                // core slightly so it reads as pressable.
                val coreInteraction = remember { MutableInteractionSource() }
                val corePressed by coreInteraction.collectIsPressedAsState()
                val coreScale by animateFloatAsState(
                    targetValue = if (corePressed) 0.92f else 1f,
                    animationSpec = tween(120),
                    label = "coreScale",
                )
                Image(
                    painter = painterResource(variant.dialDrawableRes()),
                    contentDescription = stringResource(R.string.omnitrix_selector_transform),
                    modifier = Modifier
                        .size(150.dp)
                        .scale(coreScale)
                        .graphicsLayer { rotationZ = dialRotation / 2f }
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = coreInteraction,
                            indication = null,
                            onClick = { onAssistantSelected(selected) },
                        ),
                )
                assistants.forEachIndexed { index, assistant ->
                    // Slot angle rotates with the dial; slot 'selectedIndex' ends on top.
                    val angleDeg = index * anglePerSlot - 90f + dialRotation
                    val angleRad = Math.toRadians(angleDeg.toDouble())
                    val x = (ringRadius.value * cos(angleRad)).dp
                    val y = (ringRadius.value * sin(angleRad)).dp
                    val isSelected = index == selectedIndex
                    val avatarScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.25f else 0.9f,
                        animationSpec = tween(250),
                        label = "avatarScale",
                    )
                    Box(
                        modifier = Modifier
                            .offset(x = x, y = y)
                            .size(44.dp)
                            .scale(avatarScale)
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        UIAvatar(
                            name = assistant.name.ifEmpty { defaultAssistantName },
                            value = assistant.avatar,
                            modifier = Modifier.size(40.dp),
                            onClick = { selectedIndex = index },
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = selected.name.ifEmpty { defaultAssistantName },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    onClick = {
                        onDismiss()
                        navController.navigate(Screen.AssistantDetail(selected.id.toString()))
                    }
                ) {
                    Icon(HugeIcons.Edit03, contentDescription = null)
                }
            }

            Text(
                text = stringResource(R.string.omnitrix_selector_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
