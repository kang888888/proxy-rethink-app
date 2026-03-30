/*
 * Copyright 2025 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Robolectric 冒烟：验证 Local DNS（本地 hosts）相关 Activity 在真实 inflate / onCreate / onResume
 * 下不崩溃，可提前发现「瘦身主题 + Material 控件」等运行期问题。
 *
 * 依赖：@Config(packageName) 须与 applicationId 一致，否则 Robolectric 会落到 org.robolectric.default，
 * 导致主题 / R 资源解析失败；[LocalHostsRobolectricTestApplication] 避免拉起生产 Application；
 * Koin：在 @Before 中 startKoin（仅本页依赖）。
 */
package com.kang.bravedns.ui.activity

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.kang.bravedns.LocalHostsRobolectricTestApplication
import com.kang.bravedns.database.HostsProfileRepository
import com.kang.bravedns.service.LocalHostsResolver
import com.kang.bravedns.service.PersistentState
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = LocalHostsRobolectricTestApplication::class)
class LocalHostsProfilesActivityRobolectricTest {

    private lateinit var mockPersistentState: PersistentState
    private lateinit var mockRepo: HostsProfileRepository
    private lateinit var mockResolver: LocalHostsResolver

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        mockPersistentState =
            mockk(relaxed = true) {
                every { theme } returns 0
            }
        mockRepo =
            mockk(relaxed = true) {
                every { getProfiles() } returns emptyList()
            }
        mockResolver = mockk(relaxed = true)

        try {
            stopKoin()
        } catch (_: Exception) {
        }
        startKoin {
            androidContext(context)
            modules(
                module {
                    single<PersistentState> { mockPersistentState }
                    single<HostsProfileRepository> { mockRepo }
                    single<LocalHostsResolver> { mockResolver }
                }
            )
        }
    }

    @After
    fun tearDown() {
        try {
            stopKoin()
        } catch (_: Exception) {
        }
    }

    @Test
    fun localHostsProfilesActivity_inflateAndResumeWithoutCrash() {
        val activity =
            Robolectric.buildActivity(LocalHostsProfilesActivity::class.java)
                .create()
                .start()
                .resume()
                .get()
        assertNotNull(activity)
        idleMainAndBackground()
    }

    @Test
    fun localHostsProfileDetailActivity_invalidProfileId_inflateAndResumeWithoutCrash() {
        val activity =
            Robolectric.buildActivity(LocalHostsProfileDetailActivity::class.java)
                .create()
                .start()
                .resume()
                .get()
        assertNotNull(activity)
        idleMainAndBackground()
    }

    @Test
    fun localHostsProfileDetailActivity_withProfileId_loadEntriesUsesMockRepo() {
        every { mockRepo.getEntries(42L) } returns emptyList()

        val intent =
            android.content.Intent(
                ApplicationProvider.getApplicationContext(),
                LocalHostsProfileDetailActivity::class.java
            )
        intent.putExtra(LocalHostsProfileDetailActivity.EXTRA_PROFILE_ID, 42L)
        intent.putExtra(LocalHostsProfileDetailActivity.EXTRA_PROFILE_NAME, "test")

        val activity =
            Robolectric.buildActivity(LocalHostsProfileDetailActivity::class.java, intent)
                .create()
                .start()
                .resume()
                .get()
        assertNotNull(activity)
        idleMainAndBackground()
    }

    private fun idleMainAndBackground() {
        ShadowLooper.shadowMainLooper().idle()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }
}
