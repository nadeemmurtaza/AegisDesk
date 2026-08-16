package com.newax.aegis.ui.devconsole.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.newax.aegis.R
import com.newax.aegis.ui.devconsole.DevConsoleViewModel

@Composable
fun DbTab(vm: DevConsoleViewModel) {
    val tableStats by vm.dbStats.collectAsState()
    val sqlResult  by vm.sqlResult.collectAsState()
    var sqlQuery   by remember { mutableStateOf("SELECT * FROM file_objects LIMIT 10") }
    var showSql    by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item { Spacer(Modifier.height(10.dp)) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.dev_tables_count, tableStats.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = { showSql = !showSql }) {
                    Text(if (showSql) stringResource(R.string.dev_hide_sql) else stringResource(R.string.dev_sql_editor))
                }
            }
        }

        if (showSql) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = sqlQuery,
                        onValueChange = { sqlQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dev_sql_query), fontSize = 12.sp) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        minLines = 3,
                        maxLines = 6
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.runSql(sqlQuery) },
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(stringResource(R.string.dev_run), fontSize = 12.sp)
                        }
                        if (sqlResult != null) {
                            TextButton(
                                onClick = { vm.clearSqlResult() },
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(stringResource(R.string.dev_clear), fontSize = 12.sp)
                            }
                        }
                    }
                    sqlResult?.let { result ->
                        SqlResultView(result)
                    }
                }
            }
        }

        items(tableStats) { stat ->
            TableStatRow(stat)
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TableStatRow(stat: DevConsoleViewModel.DbTableStat) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stat.table,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        val (label, color) = when {
            stat.rows < 0  -> stringResource(R.string.dev_error) to Color(0xFFEF5350)
            stat.rows == 0 -> "0"     to MaterialTheme.colorScheme.onSurfaceVariant
            else           -> stat.rows.toString() to MaterialTheme.colorScheme.primary
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun SqlResultView(result: DevConsoleViewModel.SqlResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (result.error != null) {
            Text(
                text = stringResource(R.string.dev_sql_error, result.error),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFEF5350),
                fontFamily = FontFamily.Monospace
            )
        } else {
            Text(
                text = stringResource(R.string.dev_sql_result, result.rows.size, result.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF121212))
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState())
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        result.columns.forEach { col ->
                            Text(
                                text = col,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF64B5F6)
                            )
                        }
                    }
                    HorizontalDivider(color = Color(0xFF333333), thickness = 0.5.dp)
                    result.rows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.zip(result.columns).forEach { (cell, _) ->
                                Text(
                                    text = cell.take(40),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFE0E0E0)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
