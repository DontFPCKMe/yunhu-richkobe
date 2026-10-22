package com.juhao.murexide.data

import kotlinx.serialization.Serializable

@Serializable
data class BotItem(
    val id: String,
    val name: String,
    val avatarUrl: String,
    val introduction: String = ""
)

@Serializable
data class InstructionItem(
    val id: Long,
    val botId: String,
    val name: String,
    val desc: String = "",
    val type: Int, // 1-普通指令, 2-直发指令, 5-自定义输入指令
    val hintText: String = "",
    val defaultText: String = "",
    val form: String = "",
    val botName: String = ""
)

data class InstructionPanelState(
    val isVisible: Boolean = false,
    val isLoading: Boolean = false,
    val isLoaded: Boolean = false,
    val bots: List<BotItem> = emptyList(),
    val instructions: List<InstructionItem> = emptyList()
)

// ---------- 机器人私聊指令 (instruction/web-list, JSON) ----------

@Serializable
data class InstructionWebListResponse(
    val code: Int = 0,
    val data: InstructionWebListData? = null,
    val msg: String = ""
)

@Serializable
data class InstructionWebListData(
    val list: List<InstructionWebListItem> = emptyList()
)

@Serializable
data class InstructionWebListItem(
    val id: Long = 0,
    val botId: String = "",
    val name: String = "",
    val desc: String = "",
    val instructionType: Int = 1,
    val hintText: String = "",
    val defaultText: String = "",
    val customJson: String = ""
)

fun InstructionWebListItem.toInstructionItem(botName: String = ""): InstructionItem = InstructionItem(
    id = id,
    botId = botId,
    name = name,
    desc = desc,
    type = instructionType,
    hintText = hintText,
    defaultText = defaultText,
    form = customJson,
    botName = botName
)
