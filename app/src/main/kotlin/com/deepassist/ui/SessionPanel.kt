package com.deepassist.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.deepassist.service.ChatSession
import com.deepassist.service.VoiceActivity
import com.deepassist.ui.theme.AnswerStyle
import com.deepassist.ui.theme.AssistantFont
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The session panel: a cream sheet at the bottom with a live voice indicator,
 * the current step and the conversation. Swipe the handle up to fill the screen.
 */
@Composable
fun SessionPanel(
    entries: List<ChatSession.Entry>,
    phase: VoiceActivity.Phase,
    level: Float,
    expanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onOutsideTap: () -> Unit,
    onEnd: () -> Unit
) {
    val transcript = entries.filter { it.kind == ChatSession.Kind.USER || it.kind == ChatSession.Kind.ASSISTANT }
    val step = entries.lastOrNull()
        ?.takeIf { it.kind == ChatSession.Kind.STATUS || it.kind == ChatSession.Kind.LISTENING }
        ?.text
        ?.trimEnd('.', '…', ' ')
    val label = step ?: when (phase) {
        VoiceActivity.Phase.LISTENING -> "Dinliyorum"
        VoiceActivity.Phase.THINKING -> "Düşünüyorum"
        VoiceActivity.Phase.SPEAKING -> "Konuşuyorum"
        VoiceActivity.Phase.IDLE -> null
    }

    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Asistanı kapat"
            ) { onOutsideTap() }
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(if (expanded) Modifier.fillMaxHeight() else Modifier)
                .animateContentSize(spring(stiffness = 500f))
                // Taps on the sheet itself must not count as "outside"
                .pointerInput(Unit) { detectTapGestures { } },
            shape = if (expanded) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 16.dp
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .then(if (expanded) Modifier.statusBarsPadding() else Modifier)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 18.dp)
            ) {
                AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
                    ExpandedTopBar(onCollapse = onCollapse, onEnd = onEnd)
                }
                DragHandle(expanded = expanded, onExpand = onExpand, onCollapse = onCollapse)

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VoiceBars(phase = phase, level = level)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = label.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }

                Transcript(
                    items = transcript,
                    modifier = if (expanded) {
                        Modifier.weight(1f)
                    } else {
                        Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.45f).dp)
                    }
                )
            }
        }
    }
}

@Composable
private fun ExpandedTopBar(onCollapse: () -> Unit, onEnd: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onCollapse) {
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Paneli küçült")
        }
        Text(
            "deepAssist",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = AssistantFont),
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onEnd) {
            Text("Sohbeti bitir", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DragHandle(expanded: Boolean, onExpand: () -> Unit, onCollapse: () -> Unit) {
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    val drag = remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .semantics {
                contentDescription = if (expanded) "Paneli küçült" else "Paneli büyüt"
                onClick { if (expanded) onCollapse() else onExpand(); true }
            }
            .pointerInput(expanded) {
                detectVerticalDragGestures(
                    onDragStart = { drag.floatValue = 0f },
                    onDragEnd = {
                        if (drag.floatValue < -threshold && !expanded) onExpand()
                        else if (drag.floatValue > threshold && expanded) onCollapse()
                    }
                ) { _, dy -> drag.floatValue += dy }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(width = 40.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
        )
    }
}

/**
 * Five rounded bars: they follow the microphone while listening, breathe in a
 * slow wave while thinking and move livelier while speaking.
 */
@Composable
fun VoiceBars(phase: VoiceActivity.Phase, level: Float, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "voice")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "wave"
    )
    val smoothLevel by animateFloatAsState(level, spring(stiffness = 900f), label = "level")
    val color = MaterialTheme.colorScheme.primary
    val weights = floatArrayOf(0.55f, 0.85f, 1f, 0.8f, 0.6f)

    Row(
        modifier.height(36.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until 5) {
            val fraction = when (phase) {
                VoiceActivity.Phase.LISTENING ->
                    0.22f + 0.78f * smoothLevel * weights[i] + 0.06f * sin(t * 2 + i)
                VoiceActivity.Phase.THINKING ->
                    0.28f + 0.32f * (1 + sin(t - i * 0.7f)) / 2
                VoiceActivity.Phase.SPEAKING ->
                    0.3f + 0.65f * abs(sin(t * 1.7f + i * 0.9f)) * weights[i]
                VoiceActivity.Phase.IDLE -> 0.2f
            }.coerceIn(0.15f, 1f)
            Box(
                Modifier
                    .width(6.dp)
                    .height((36 * fraction).dp)
                    .background(color, RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
private fun Transcript(items: List<ChatSession.Entry>, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(items.size, items.lastOrNull()?.text) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(items) { _, entry ->
            if (entry.kind == ChatSession.Kind.USER) UserLine(entry) else AssistantLine(entry.text)
        }
    }
}

@Composable
private fun UserLine(entry: ChatSession.Entry) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text = entry.text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontStyle = if (entry.partial) FontStyle.Italic else FontStyle.Normal
            ),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .alpha(if (entry.partial) 0.75f else 1f)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
                )
                .padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun AssistantLine(text: String) {
    Text(
        text = text,
        style = AnswerStyle,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(end = 24.dp)
    )
}
