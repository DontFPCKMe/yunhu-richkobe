package com.juhao.murexide.ui.components

import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp

@Composable
fun UnreadCountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Badge(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            fontSize = 10.sp
        )
    }
}
