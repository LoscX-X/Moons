package com.blanoir.moons.client.ui.clickgui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.blanoir.moons.client.module.framework.ModuleRegistry.Module

/** Movable category panel layout. */
@Composable
internal fun CategoryPanel(
    category: String,
    modules: List<Module>,
    position: Offset,
    maxBodyHeight: Dp,
    active: Boolean,
    collapsed: Boolean,
    expanded: MutableMap<String, Boolean>,
    bindingModuleId: String?,
    onActivate: () -> Unit,
    onMoveBy: (Offset) -> Unit,
    onMoveFinished: () -> Unit,
    onCollapse: () -> Unit,
    onExpandModule: (String) -> Unit,
    onBindingModuleChange: (String?) -> Unit,
    onMutated: () -> Unit,
) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(6.dp)
    val categoryIcon = remember(category) { categoryIconId(category)?.let(::guiIcon) }
    Column(
        Modifier.offset {
                IntOffset(
                    with(density) { position.x.dp.roundToPx() },
                    with(density) { position.y.dp.roundToPx() },
                )
            }
            .width(PANEL_WIDTH.dp)
            .zIndex(if (active) 50f else 1f)
            .shadow(if (active) 16.dp else 9.dp, shape)
            .clip(shape)
            .background(PanelStyle.panel)
            .border(
                1.dp,
                if (active) PanelStyle.accent.copy(alpha = 0.45f) else PanelStyle.border,
                shape,
            )
    ) {
        Row(
            Modifier.fillMaxWidth()
                .height(27.dp)
                .background(PanelStyle.header)
                .pointerInput(category) {
                    detectDragGestures(
                        onDragStart = { onActivate() },
                        onDragEnd = onMoveFinished,
                        onDragCancel = onMoveFinished,
                        onDrag = { change, amount ->
                            change.consume()
                            val dx = amount.x / density.density
                            val dy = amount.y / density.density
                            onMoveBy(Offset(dx, dy))
                        },
                    )
                }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (categoryIcon != null) {
                Image(
                    bitmap = categoryIcon,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(PanelStyle.muted),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                category,
                color = PanelStyle.text,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 9.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CollapseButton(collapsed = collapsed, onClick = onCollapse)
        }

        AnimatedVisibility(
            visible = !collapsed,
            enter =
                expandVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                    fadeIn(animationSpec = tween(160)),
            exit =
                shrinkVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                    fadeOut(animationSpec = tween(120)),
        ) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxBodyHeight)) {
                if (modules.isEmpty()) {
                    item {
                        Text(
                            "No matches",
                            color = PanelStyle.dim,
                            fontSize = 8.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                items(modules, key = { it.id() }) { module ->
                    ModuleRow(
                        module = module,
                        expanded = expanded[module.id()] == true,
                        bindingModuleId = bindingModuleId,
                        onExpand = { onExpandModule(module.id()) },
                        onBindingModuleChange = onBindingModuleChange,
                        onMutated = onMutated,
                    )
                }
            }
        }
    }
}
