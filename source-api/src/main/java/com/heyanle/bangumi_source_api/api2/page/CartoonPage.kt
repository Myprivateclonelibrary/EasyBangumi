package com.heyanle.bangumi_source_api.api2.page

/**
 * 番剧页抽象
 * Created by HeYanLe on 2023/2/20 16:00.
 * https://github.com/heyanLE
 */
interface CartoonPage {
    // 页面标题
    val label: String

    // 隶属的源 key
    val sourceKey: String

    // 是否启动新界面展示
    val newScreen: Boolean
}