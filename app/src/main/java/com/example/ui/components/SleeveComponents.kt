package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanSplash
import com.example.ui.theme.MidnightInk
import com.example.ui.theme.OceanDrift
import com.example.ui.theme.PaperWhite
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SilverMist

/**
 * Sleeve-style 24dp rounded card with subtle 1dp hairline border
 */
@Composable
fun SleeveCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, borderColor, RoundedCornerShape(24.dp)),
        color = backgroundColor,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(padding),
            content = content
        )
    }
}

/**
 * Sleeve Eyebrow label - uppercase, 0.05em tracking, Ocean Drift accent
 */
@Composable
fun SleeveEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = OceanDrift
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
        modifier = modifier
    )
}

/**
 * Sleeve Primary filled pill button (#000000 with white text) or Outlined variant
 */
@Composable
fun SleevePillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = true,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    testTag: String = "sleeve_pill_button"
) {
    val bgColor = if (isPrimary) {
        if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    } else {
        Color.Transparent
    }
    val textColor = if (isPrimary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground
    val borderModifier = if (!isPrimary) {
        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9999.dp))
    } else Modifier

    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(9999.dp))
            .then(borderModifier)
            .testTag(testTag)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        color = bgColor,
        shape = RoundedCornerShape(9999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Sleeve Gradient / Status pill badge
 */
@Composable
fun SleeveBadge(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = PaperWhite,
    textColor: Color = MidnightInk,
    borderColor: Color = SilverMist,
    brush: Brush? = null,
    icon: ImageVector? = null
) {
    val backgroundModifier = if (brush != null) {
        Modifier.background(brush, RoundedCornerShape(9999.dp))
    } else {
        Modifier.background(backgroundColor, RoundedCornerShape(9999.dp))
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9999.dp))
            .then(backgroundModifier)
            .border(1.dp, borderColor, RoundedCornerShape(9999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = textColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Sleeve Size / Mode Toggle Button Row (e.g. 3s / 5s / 10s or Strict / Relaxed)
 */
@Composable
fun <T> SleeveToggleGroup(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    itemLabel: (T) -> String,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "toggle"
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9999.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { item ->
            val isSelected = item == selectedItem
            val animatedBg by animateColorAsState(
                targetValue = if (isSelected) OceanDrift else Color.Transparent,
                label = "toggle_bg"
            )
            val animatedText by animateColorAsState(
                targetValue = if (isSelected) PureWhite else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "toggle_text"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 40.dp)
                    .clip(RoundedCornerShape(9999.dp))
                    .background(animatedBg)
                    .testTag("${testTagPrefix}_${itemLabel(item)}")
                    .clickable(role = Role.RadioButton, onClick = { onItemSelected(item) }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = itemLabel(item),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = animatedText,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Sleeve Announcement / Status Pill banner
 */
@Composable
fun SleeveAnnouncementPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else Modifier

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9999.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9999.dp))
            .then(clickModifier),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(9999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = OceanDrift,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
