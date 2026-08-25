package com.itsluminous.samaroh.core.testing

import android.content.Context
import androidx.room.Room
import com.itsluminous.samaroh.core.database.SamarohDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps `Dispatchers.Main` for a test dispatcher for the duration of a test —
 * required by any test touching ViewModels or `viewModelScope`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/** In-memory [SamarohDatabase] for DAO/repository tests (Robolectric or instrumented). */
fun inMemoryDatabase(context: Context): SamarohDatabase =
    Room
        .inMemoryDatabaseBuilder(context, SamarohDatabase::class.java)
        .allowMainThreadQueries()
        .build()
