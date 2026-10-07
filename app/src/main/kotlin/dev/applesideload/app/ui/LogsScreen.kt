package dev.applesideload.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.applesideload.core.LogLevel
import dev.applesideload.core.LogLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The activity log, newest first. */
@Composable
fun LogsScreen(logs: List<LogLine>) {
    if (logs.isEmpty()) {
        ScreenColumn {
            EmptyState(
                icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                title = "Nothing logged yet",
                body = "What the app does with the iPhone and with Apple is written here as it happens."
            )
        }
        return
    }
    val newestFirst = remember(logs) { logs.asReversed() }
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        item {
            Hint(
                "Newest first. Apple IDs, keys, tokens and device identifiers are replaced before " +
                    "anything is written here; device names and network addresses stay.",
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        items(newestFirst, key = { it.sequence }) { line ->
            LogRow(line, clock.format(Date(line.at)))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun LogRow(line: LogLine, time: String) {
    val scheme = MaterialTheme.colorScheme
    val levelColor: Color = when (line.level) {
        LogLevel.ERROR -> scheme.error
        LogLevel.WARN -> AppColors.status.warning
        LogLevel.INFO -> scheme.primary
        LogLevel.DEBUG -> scheme.onSurfaceVariant
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(time, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text(
                line.level.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = levelColor
            )
            Text(line.tag.label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        SelectionContainer {
            Text(
                line.message,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (line.level == LogLevel.ERROR) scheme.error else scheme.onSurface
            )
        }
    }
}
