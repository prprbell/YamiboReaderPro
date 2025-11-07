package org.shirakawatyu.yamibo.novel.ui.widget

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.text.isDigitsOnly

@Preview
@Composable
fun NumInputWithConfirm(
    modifier: Modifier = Modifier,
    label: String = "asdasd",
    range: IntRange = 1..2,
    onConfirm: (value: Int) -> Unit = {}
) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        TextField(modifier = Modifier.weight(7f), label = { Text(label) }, value = value, isError = error, onValueChange = {
            if (it.isEmpty() || !it.isDigitsOnly())
                error = true
            else {
                error = false
                value = it
            }
        })
        Button(modifier = Modifier.padding(10.dp, 0.dp, 0.dp, 0.dp).weight(2f), onClick = {
            if (!range.contains(value.toInt())) error = true
            else onConfirm(value.toInt())
        }) {
            Icon(imageVector = Icons.Filled.Check, contentDescription =  "", tint = Color.White)
        }
    }
}