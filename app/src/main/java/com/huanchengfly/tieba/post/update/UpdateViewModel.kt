package com.huanchengfly.tieba.post.update

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 更新模块 ViewModel：两个入口（启动检查弹窗 + 关于页手动检查）共用，
 * 状态与逻辑都在单例 [UpdateManager] 中，这里只做转发。
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val manager: UpdateManager
) : ViewModel() {

    val state = manager.state

    /**
     * 启动自动检查。debug 构建可通过 [mockRoot] 覆盖 API 根地址用于本地 mock 验收。
     */
    fun startupCheck(mockRoot: String? = null) = manager.check(UpdateSource.STARTUP, mockRoot)

    /**
     * 关于页手动检查（不节流、不受"本次忽略"限制、失败可见）。
     */
    fun manualCheck() = manager.check(UpdateSource.MANUAL)

    /**
     * "本次忽略"当前版本（强更版本不可忽略）。
     */
    fun ignoreCurrentVersion() {
        val available = manager.state.value as? UpdateUiState.Available ?: return
        manager.ignore(available.release.tagName, available.force)
    }

    fun download() = manager.download()

    fun install() = manager.install()
}
