/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
*/

package me.kavishdevar.librepods.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.theme.sectionHeader

@Composable
fun HeartRateCard(
    enabled: Boolean,
    streaming: Boolean,
    bpm: Int?,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val materialDesign = LocalDesignSystem.current == DesignSystem.Material
    val displayedBpm = bpm.takeIf { enabled && streaming }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .background(
                    if (materialDesign) Color.Transparent
                    else MaterialTheme.colorScheme.surfaceContainer
                )
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = if (materialDesign) 8.dp else 4.dp)
        ) {
            Text(
                text = stringResource(R.string.heart_rate),
                color = if (materialDesign) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.sectionHeader,
                style = MaterialTheme.typography.labelSmallEmphasized
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 92.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(if (materialDesign) 24.dp else 28.dp)
                )
                .clip(RoundedCornerShape(if (materialDesign) 24.dp else 28.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = stringResource(R.string.heart_rate),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Text(
                text = when {
                    !enabled -> stringResource(R.string.heart_rate_off)
                    !streaming -> stringResource(R.string.heart_rate_measuring)
                    else -> stringResource(R.string.heart_rate_live)
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = displayedBpm?.toString() ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.bpm),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(14.dp))

            if (materialDesign) {
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            } else {
                StyledSwitch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
        }
    }
}
