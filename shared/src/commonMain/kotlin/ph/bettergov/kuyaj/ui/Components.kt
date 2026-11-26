package ph.bettergov.kuyaj.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.Text
import com.composeunstyled.UnstyledButton
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** Press feedback without ripple: a slight shrink and dim, which reads as native on any skin. */
@Composable
private fun pressFeedback(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "press")
    return Modifier.scale(scale).alpha(if (pressed) 0.85f else 1f)
}

enum class ButtonKind { Primary, Secondary, Ghost }

@Composable
fun KButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
) {
    val c = LocalKuyaColors.current
    val source = remember { MutableInteractionSource() }
    val (bg, fg, border) = when (kind) {
        ButtonKind.Primary -> Triple(c.primary, c.onPrimary, null)
        ButtonKind.Secondary -> Triple(c.surface, c.ink, BorderStroke(1.dp, c.border))
        ButtonKind.Ghost -> Triple(Color.Transparent, c.primaryText, null)
    }
    val shape = RoundedCornerShape(12.dp)
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp),
        modifier = modifier
            .then(pressFeedback(source))
            .clip(shape)
            .background(bg)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        Text(text, style = Type.smallMedium, color = fg)
    }
}

@Composable
fun IconBtn(
    icon: DrawableResource,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalKuyaColors.current.muted,
    background: Color = Color.Transparent,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
    shape: Shape = CircleShape,
) {
    val source = remember { MutableInteractionSource() }
    UnstyledButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        modifier = modifier.then(pressFeedback(source)).size(size).clip(shape).background(background),
    ) {
        UnstyledIcon(painterResource(icon), contentDescription = description, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalKuyaColors.current.primaryText,
    background: Color = LocalKuyaColors.current.primarySoft,
    icon: Painter? = null,
    dot: Color? = null,
) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dot != null) Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        if (icon != null) UnstyledIcon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        Text(text, style = Type.caption, color = color)
    }
}

/** Three dots that breathe while Kuya J looks through the records. */
@Composable
fun TypingDots(color: Color = LocalKuyaColors.current.faint) {
    val t = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(520, delayMillis = i * 160), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(color))
        }
    }
}

@Composable
fun icon(id: DrawableResource): Painter = painterResource(id)
