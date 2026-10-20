package com.huanchengfly.tieba.post.utils

import android.provider.Settings
import com.huanchengfly.tieba.post.App

/**
 * 关注流 followType 实验开关（临时代码，验证出正确取值后连同调用点一并删除）。
 *
 * 背景：「动态 → 关注」标签调用 /c/f/concern/userlike 时硬编码 followType=1，
 * 实测（账号关注 0 人时）返回的全是账号自己的动态，不是已关注吧的新帖，
 * 无法满足「只看已关注吧」的需求。本开关用于在不重新构建的情况下逐个
 * 试验 followType 取值：
 *
 *   adb shell settings put global tieba_debug_follow_type 0
 *
 * 设置后刷新会使用该取值，并重置增量游标（lastRequestUnix=0 且不回写），
 * 保证每次试验拿到的都是完整首页，不受游标影响。删除该 global 设置即恢复原行为。
 */
object ConcernFeedDebug {
    const val KEY = "tieba_debug_follow_type"

    fun followTypeOverride(): Int? = try {
        Settings.Global.getInt(App.INSTANCE.contentResolver, KEY, -1).takeIf { it >= 0 }
    } catch (e: Exception) {
        null
    }

    /** 调试态下不使用、也不回写增量游标 */
    fun resetCursor(): Boolean = followTypeOverride() != null
}
