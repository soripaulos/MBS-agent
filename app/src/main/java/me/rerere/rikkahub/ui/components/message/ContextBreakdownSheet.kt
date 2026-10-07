package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.ContextReport
import me.rerere.rikkahub.utils.formatNumber

/**
 * "Where did these tokens come from?" — the request breakdown recorded by
 * [me.rerere.rikkahub.data.ai.ContextDiagnostics] for one reply.
 */
@Composable
fun ContextBreakdownSheet(report: ContextReport?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.context_breakdown_title), style = MaterialTheme.typography.titleLarge)
            if (report == null) {
                Text(
                    stringResource(R.string.context_breakdown_missing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val total = report.estimatedTotal.coerceAtLeast(1)
            Text(
                stringResource(
                    R.string.context_breakdown_summary,
                    report.estimatedTotal.formatNumber(),
                    report.modelName,
                    report.toolCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            report.findings.forEach { finding ->
                Text("• $finding", style = MaterialTheme.typography.bodyMedium)
            }
            report.sections.sortedByDescending { it.tokens }.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(section.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(
                            "${section.tokens.formatNumber()} · ${section.tokens * 100 / total}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { section.tokens.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    section.items.sortedByDescending { it.tokens }.take(10).forEach { item ->
                        Row {
                            Column(Modifier.weight(1f)) {
                                Text(item.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                item.detail?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Text(item.tokens.formatNumber(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.context_breakdown_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
