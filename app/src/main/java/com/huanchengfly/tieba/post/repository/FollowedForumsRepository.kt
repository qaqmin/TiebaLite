package com.huanchengfly.tieba.post.repository

import android.util.Log
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.utils.appPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 已关注吧列表仓库。
 *
 * 数据来源：userLikeForum 分页接口（官方「关注的吧」列表）。拉全量后缓存到
 * DataStore（forumId 字符串集合）供推荐流过滤使用；关注/取关操作会使服务端
 * 列表变化，因此提供 stale 标记：置脏后下次读取自动重新拉全量。
 *
 * 过滤语义：thread.forumId ∈ 关注吧集合 即保留。
 */
object FollowedForumsRepository {
    private const val TAG = "FollowedForums"
    private val prefs = App.INSTANCE.appPreferences
    private val scope = CoroutineScope(Dispatchers.IO)

    private val cacheFlow = MutableStateFlow<Set<Long>?>(null)
    private val mutex = Mutex()

    /** 当前缓存（可能为 null = 尚未加载） */
    val current: Set<Long>?
        get() = cacheFlow.value

    /** 关注/取关后调用：置脏，下次 ensureLoaded 重新拉全量 */
    fun invalidate() {
        cacheFlow.value = null
    }

    /**
     * 确保拿到关注吧集合。缓存有效直接返回；否则先读 DataStore 落底，
     * 再异步刷新（首次无缓存时挂起等待网络结果）。
     */
    suspend fun ensureLoaded(): Set<Long> = mutex.withLock {
        cacheFlow.value?.let { return it }

        val persisted = prefs.followedForumIds
        if (persisted != null) {
            cacheFlow.value = persisted
            refreshAsync()
            return persisted
        }
        val fresh = fetchAll()
        cacheFlow.value = fresh
        prefs.followedForumIds = fresh
        fresh
    }

    /** 后台刷新缓存；失败静默（保留旧缓存） */
    private fun refreshAsync() {
        scope.launch {
            runCatching {
                val fresh = fetchAll()
                cacheFlow.value = fresh
                prefs.followedForumIds = fresh
            }.onFailure { Log.w(TAG, "refresh followed forums failed", it) }
        }
    }

    /** 分页拉全关注吧 id 集合 */
    private suspend fun fetchAll(): Set<Long> {
        val uid = AccountUtil.getUid() ?: return emptySet()
        val ids = mutableSetOf<Long>()
        var page = 1
        while (true) {
            val bean = TiebaApi.getInstance().userLikeForumFlow(uid, page).first()
            bean.forumList.forumList.forEach { ids.add(it.id.toLongOrNull() ?: return@forEach) }
            bean.commonForumList.forumList.forEach { ids.add(it.id.toLongOrNull() ?: return@forEach) }
            if (bean.hasMore == "1" && page < 50) page++ else break
        }
        Log.i(TAG, "fetched followed forums: ${ids.size} forums")
        return ids
    }
}
