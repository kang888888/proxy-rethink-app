/*
 * Copyright 2025 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package com.kang.bravedns

import android.app.Application
import com.kang.bravedns.R

/**
 * 供 Local hosts 相关 Robolectric 测试使用：不启动 RethinkDnsApplication / Koin，
 * 仅设置 AppCompat/Material 主题，避免 AppCompatActivity 在 inflate 前崩溃。
 */
class LocalHostsRobolectricTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        setTheme(R.style.AppTheme)
    }
}
