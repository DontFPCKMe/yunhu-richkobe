package com.juhao.murexide.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juhao.murexide.data.InstructionItem
import com.juhao.murexide.ui.components.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val formJson = Json { ignoreUnknownKeys = true }

private data class FormField(
    val id: String,
    val type: String,
    val title: String,
    val options: List<String>,
    val default: String,
    val raw: JsonObject
)

private fun parseFormFields(form: String): List<FormField> {
    if (form.isBlank()) return emptyList()
    return try {
        formJson.parseToJsonElement(form).jsonArray.mapIndexed { index, el ->
            val obj = el.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: "input"
            val propsValue = obj["propsValue"] as? JsonObject
            val title = propsValue?.get("label")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: obj["title"]?.jsonPrimitive?.content
                ?: "字段${index + 1}"
            val optionsStr = propsValue?.get("options")?.jsonPrimitive?.content ?: ""
            val options = optionsStr.split("#").map { it.trim() }.filter { it.isNotEmpty() }
            val default = propsValue?.get("value")?.jsonPrimitive?.content ?: ""
            val id = obj["id"]?.jsonPrimitive?.content ?: index.toString()
            FormField(id, type, title, options, default, obj)
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * 自定义输入指令 (type 5) 表单对话框。
 * ⚠️ 发送用的 form 字段精确格式官方文档未提供，此处按「原结构 + 注入 value」的最可能方案序列化，
 * 需实机/抓包核对后按需调整 [buildFormOutput]。
 */
@Composable
fun InstructionFormDialog(
    item: InstructionItem,
    onDismiss: () -> Unit,
    onSubmit: (formJson: String) -> Unit
) {
    val fields = remember(item.id, item.form) { parseFormFields(item.form) }

    // 各字段的输入值
    val textValues = remember { mutableStateMapOf<String, String>() }
    val switchValues = remember { mutableStateMapOf<String, Boolean>() }
    val checkboxValues = remember { mutableStateMapOf<String, Set<String>>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            if (fields.isEmpty()) {
                Text("该指令无可填写字段，直接发送即可。")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    fields.forEach { field ->
                        FormFieldView(
                            field = field,
                            textValues = textValues,
                            switchValues = switchValues,
                            checkboxValues = checkboxValues
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSubmit(buildFormOutput(fields, textValues, switchValues, checkboxValues))
            }) { Text("发送") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun FormFieldView(
    field: FormField,
    textValues: SnapshotStateMap<String, String>,
    switchValues: SnapshotStateMap<String, Boolean>,
    checkboxValues: SnapshotStateMap<String, Set<String>>
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = field.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        when (field.type) {
            "switch" -> {
                val checked = switchValues[field.id] ?: (field.default == "true")
                StyledSwitch(
                    checked = checked,
                    onCheckedChange = { switchValues[field.id] = it }
                )
            }
            "radio", "select" -> {
                val selected = textValues[field.id] ?: field.default
                Column {
                    field.options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { textValues[field.id] = option }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected == option,
                                onClick = { textValues[field.id] = option }
                            )
                            Text(option, fontSize = 13.sp)
                        }
                    }
                }
            }
            "checkbox" -> {
                val current = checkboxValues[field.id] ?: emptySet()
                Column {
                    field.options.forEach { option ->
                        val checked = option in current
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    checkboxValues[field.id] =
                                        if (checked) current - option else current + option
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    checkboxValues[field.id] =
                                        if (isChecked) current + option else current - option
                                }
                            )
                            Text(option, fontSize = 13.sp)
                        }
                    }
                }
            }
            else -> { // input / textarea 及其他
                OutlinedTextField(
                    value = textValues[field.id] ?: field.default,
                    onValueChange = { textValues[field.id] = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = field.type != "textarea",
                    minLines = if (field.type == "textarea") 3 else 1
                )
            }
        }
    }
}

/** 把用户填写的值注入回原字段结构（附加 value 键），序列化为 JSON 数组字符串。 */
private fun buildFormOutput(
    fields: List<FormField>,
    textValues: Map<String, String>,
    switchValues: Map<String, Boolean>,
    checkboxValues: Map<String, Set<String>>
): String {
    val out = fields.map { field ->
        val valueElement: JsonElement = when (field.type) {
            "switch" -> JsonPrimitive(switchValues[field.id] ?: (field.default == "true"))
            "checkbox" -> JsonArray((checkboxValues[field.id] ?: emptySet()).map { JsonPrimitive(it) })
            else -> JsonPrimitive(textValues[field.id] ?: field.default)
        }
        val map = field.raw.toMutableMap()
        map["value"] = valueElement
        JsonObject(map)
    }
    return formJson.encodeToString(JsonArray.serializer(), JsonArray(out))
}
