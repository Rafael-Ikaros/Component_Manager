package com.componentvault.android.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.model.StockUsageSummary
import kotlin.math.roundToInt

@Composable
internal fun StockUsageDonut(
    summary: StockUsageSummary,
    modifier: Modifier = Modifier,
) {
    val percentage = (summary.issuedFraction * 100).roundToInt()
    val accessibilityLabel = stringResource(
        R.string.inventory_usage_summary,
        summary.remaining,
        summary.issued,
    )
    val remainingColor = MaterialTheme.colorScheme.primary
    val issuedColor = MaterialTheme.colorScheme.tertiary
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = accessibilityLabel
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(44.dp)) {
                val strokeWidth = 6.dp.toPx()
                val inset = strokeWidth / 2
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                if (summary.total == 0L) {
                    drawArc(
                        color = emptyColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(strokeWidth, cap = StrokeCap.Round),
                    )
                } else {
                    drawArc(
                        color = remainingColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(strokeWidth),
                    )
                    if (summary.issued > 0) {
                        drawArc(
                            color = issuedColor,
                            startAngle = -90f,
                            sweepAngle = summary.issuedFraction * 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(strokeWidth, cap = StrokeCap.Round),
                        )
                    }
                }
            }
            Text(
                text = "$percentage%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = stringResource(R.string.inventory_usage_issued_value, summary.issued),
            style = MaterialTheme.typography.labelMedium,
            color = issuedColor,
        )
    }
}
