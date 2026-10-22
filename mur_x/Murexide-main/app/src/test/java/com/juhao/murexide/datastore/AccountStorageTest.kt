package com.juhao.murexide.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AccountStorageTest {
    @Test
    fun `relogin replaces old token and removes duplicate account records`() {
        val accounts = listOf(
            account(id = "user-1", token = "old-token"),
            account(id = "user-2", token = "other-token"),
            account(id = "user-1", token = "duplicate-old-token")
        )

        val result = upsertAccountInList(
            accounts = accounts,
            account = account(id = "user-1", token = "new-token"),
            moveToFront = true
        )

        assertEquals(listOf("user-1", "user-2"), result.map { it.id })
        assertEquals("new-token", result.single { it.id == "user-1" }.token)
    }

    @Test
    fun `validation removes temporary account and overwrites existing real account`() {
        val accounts = listOf(
            account(id = "real-id", token = "stale-token"),
            account(id = "temporary-id", token = "fresh-token"),
            account(id = "user-2", token = "other-token")
        )

        val result = upsertAccountInList(
            accounts = accounts,
            account = account(id = "real-id", token = "fresh-token"),
            obsoleteAccountId = "temporary-id",
            moveToFront = true
        )

        assertEquals(1, result.count { it.id == "real-id" })
        assertEquals("fresh-token", result.first().token)
        assertFalse(result.any { it.id == "temporary-id" })
    }

    private fun account(id: String, token: String) = UserAccount(
        username = id,
        id = id,
        token = token,
        isValidated = true
    )
}
