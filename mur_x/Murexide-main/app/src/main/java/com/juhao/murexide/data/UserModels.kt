package com.juhao.murexide.data

import kotlinx.serialization.Serializable

/** 修改个人资料请求 */
@Serializable
data class SaveUserDataRequest(
    val introduction: String,
    val gender: Int, // 1-男，2-女，3-其他
    val birthday: Long, // 生日时间戳
    val province: String,
    val city: String,
    val district: String,
    val locationCode: String
)

/** 获取个人资料响应 */
@Serializable
data class UserDataResponse(
    val code: Int,
    val data: UserDataWrapper? = null,
    val msg: String
)

@Serializable
data class UserDataWrapper(
    val data: UserProfileData
)

@Serializable
data class UserProfileData(
    val id: Long,
    val userId: String,
    val lastLoginTime: Long,
    val update_time: Long,
    val introduction: String,
    val gender: Int,
    val birthday: Long,
    val province: String,
    val city: String,
    val district: String,
    val locationCode: String
)
