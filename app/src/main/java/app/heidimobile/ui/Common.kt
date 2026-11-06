@file:OptIn(ExperimentalMaterial3Api::class)

package app.heidimobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heidimobile.db.QueryResult
import app.heidimobile.db.SqlTokenizer
import java.util.Locale

/** Result grid. HeidiSQL's VirtualTrees based data grid, reduced to what a phone needs. */
@Composable
fun DataGrid(
    result: QueryResult,
    modifier: Modifier = Modifier,
    sortColumn: String? = null,
    sortDesc: Boolean = false,
    onHeaderClick: ((String) -> Unit)? = null,
    onRowClick: ((Int) -> Unit)? = null
) {
    if (result.columns.isEmpty()) return
    val widths: List<Dp> = remember(result) {
        result.columns.indices.map { i ->
            var maxLen = result.columns[i].length + 3
            for (r in result.rows.take(60)) {
                val l = (r.getOrNull(i)?.length ?: 4).coerceAtMost(40)
                if (l > maxLen) maxLen = l
            }
            (maxLen * 8 + 20).coerceIn(80, 300).dp
        }
    }
    val total = widths.fold(0.dp) { a, b -> a + b }
    val stripe = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

    Box(modifier.horizontalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.width(total).background(MaterialTheme.colorScheme.surfaceVariant)) {
                result.columns.forEachIndexed { i, c ->
                    val arrow = if (c == sortColumn) (if (sortDesc) " ▼" else " ▲") else ""
                    var m = Modifier.width(widths[i])
                    if (onHeaderClick != null) m = m.clickable { onHeaderClick(c) }
                    Text(
                        c + arrow, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = m.padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
            }
            if (result.rows.isEmpty()) {
                Text("No rows", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.outline)
            }
            LazyColumn(Modifier.width(total)) {
                itemsIndexed(result.rows) { idx, row ->
                    var m = Modifier.width(total)
                    if (onRowClick != null) m = m.clickable { onRowClick(idx) }
                    Row(m.background(if (idx % 2 == 1) stripe else Color.Transparent)) {
                        for (i in result.columns.indices) {
                            val v = row.getOrNull(i)
                            Text(
                                v?.take(300) ?: "NULL",
                                color = if (v == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                                fontStyle = if (v == null) FontStyle.Italic else FontStyle.Normal,
                                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(widths[i]).padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Minimal SQL syntax highlighting (HeidiSQL uses SynEdit with a configurable highlighter). */
class SqlHighlighter(private val dark: Boolean) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val src = text.text
        if (src.length > 40_000) return TransformedText(text, OffsetMapping.Identity)
        val keyword = SpanStyle(color = if (dark) Color(0xFF82B1FF) else Color(0xFF1565C0), fontWeight = FontWeight.Bold)
        val string = SpanStyle(color = if (dark) Color(0xFFA5D6A7) else Color(0xFF2E7D32))
        val comment = SpanStyle(color = Color(0xFF9E9E9E), fontStyle = FontStyle.Italic)
        val number = SpanStyle(color = if (dark) Color(0xFFFFB74D) else Color(0xFFE65100))
        val b = AnnotatedString.Builder(src)
        for (t in SqlTokenizer.tokenize(src)) {
            val style = when (t.kind) {
                SqlTokenizer.Kind.COMMENT -> comment
                SqlTokenizer.Kind.STRING -> string
                SqlTokenizer.Kind.NUMBER -> number
                SqlTokenizer.Kind.WORD -> if (src.substring(t.start, t.end).uppercase() in KEYWORDS) keyword else null
            }
            if (style != null) b.addStyle(style, t.start, t.end)
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }

    companion object {
        private val KEYWORDS = (
            "SELECT FROM WHERE AND OR NOT NULL IS IN LIKE BETWEEN INSERT INTO VALUES UPDATE SET DELETE CREATE TABLE ALTER DROP " +
                "INDEX VIEW TRIGGER PROCEDURE FUNCTION DATABASE SCHEMA JOIN INNER LEFT RIGHT OUTER FULL CROSS ON AS GROUP BY ORDER " +
                "HAVING LIMIT OFFSET UNION ALL DISTINCT CASE WHEN THEN ELSE END EXISTS PRIMARY KEY FOREIGN REFERENCES DEFAULT " +
                "UNIQUE CHECK CONSTRAINT AUTO_INCREMENT TRUNCATE SHOW DESCRIBE EXPLAIN USE BEGIN COMMIT ROLLBACK TRANSACTION WITH " +
                "RETURNING ASC DESC COUNT SUM AVG MIN MAX IF REPLACE PRAGMA TEMPORARY TEMP RENAME TO ADD COLUMN ENGINE CHARSET COLLATE"
            ).split(' ').toSet()
    }
}

@Composable
fun ErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Error") },
        text = { Text(message, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    number: Boolean = false,
    password: Boolean = false,
    minLines: Int = 1,
    mono: Boolean = false,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, modifier = modifier,
        enabled = enabled,
        singleLine = minLines == 1, minLines = minLines,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else if (password) KeyboardType.Password else KeyboardType.Text,
            autoCorrect = false
        ),
        textStyle = if (mono) TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp) else LocalTextStyle.current
    )
}

fun formatSize(bytes: Long?): String {
    if (bytes == null) return ""
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return if (i == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", v, units[i])
}
