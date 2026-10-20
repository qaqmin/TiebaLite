package com.huanchengfly.tieba.post.ui.page.main.explore.personalized

import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.AgreeBean
import com.huanchengfly.tieba.post.api.models.CommonResponse
import com.huanchengfly.tieba.post.api.models.protos.ThreadInfo
import com.huanchengfly.tieba.post.api.models.protos.personalized.DislikeReason
import com.huanchengfly.tieba.post.api.models.protos.personalized.PersonalizedResponse
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
import com.huanchengfly.tieba.post.arch.ImmutableHolder
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.arch.wrapImmutable
import com.huanchengfly.tieba.post.models.DislikeBean
import com.huanchengfly.tieba.post.repository.FollowedForumsRepository
import com.huanchengfly.tieba.post.repository.PersonalizedRepository
import com.huanchengfly.tieba.post.ui.models.ThreadItemData
import com.huanchengfly.tieba.post.ui.models.distinctById
import com.huanchengfly.tieba.post.utils.appPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

@Stable
@HiltViewModel
class PersonalizedViewModel @Inject constructor() :
    BaseViewModel<PersonalizedUiIntent, PersonalizedPartialChange, PersonalizedUiState, PersonalizedUiEvent>() {
    override fun createInitialState(): PersonalizedUiState = PersonalizedUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<PersonalizedUiIntent, PersonalizedPartialChange, PersonalizedUiState> =
        ExplorePartialChangeProducer

    override fun dispatchEvent(partialChange: PersonalizedPartialChange): UiEvent? =
        when (partialChange) {
            is PersonalizedPartialChange.Refresh.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is PersonalizedPartialChange.LoadMore.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is PersonalizedPartialChange.Refresh.Success -> PersonalizedUiEvent.RefreshSuccess(
                partialChange.data.size
            )

            else -> null
        }

    private object ExplorePartialChangeProducer : PartialChangeProducer<PersonalizedUiIntent, PersonalizedPartialChange, PersonalizedUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<PersonalizedUiIntent>): Flow<PersonalizedPartialChange> =
            merge(
                intentFlow.filterIsInstance<PersonalizedUiIntent.Refresh>().flatMapConcat { produceRefreshPartialChange() },
                intentFlow.filterIsInstance<PersonalizedUiIntent.LoadMore>().flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<PersonalizedUiIntent.Dislike>().flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<PersonalizedUiIntent.Agree>().flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<PersonalizedUiIntent.SetFollowMode>().flatMapConcat { it.producePartialChange() },
            )

        /** 「只看已关注」模式下按 forumId 过滤；空列表（未登录/加载失败）不过滤，避免整页空白 */
        private suspend fun filterFollowedOnly(data: List<ImmutableHolder<ThreadInfo>>): List<ImmutableHolder<ThreadInfo>> {
            val followed = FollowedForumsRepository.ensureLoaded()
            if (followed.isEmpty()) return data
            return data.filter { it.get { forumId } in followed }
        }

        private fun produceRefreshPartialChange(): Flow<PersonalizedPartialChange.Refresh> =
            PersonalizedRepository
                .personalizedFlow(1, 1)
                .map<PersonalizedResponse, PersonalizedPartialChange.Refresh> { response ->
                    val data = response.toData()
                        .filter {
                            !App.INSTANCE.appPreferences.blockVideo || it.get { videoInfo } == null
                        }
                        .filter { it.get { ala_info } == null }
                        .let {
                            if (App.INSTANCE.appPreferences.personalizedFollowedOnly) filterFollowedOnly(it) else it
                        }
                    val threadPersonalizedData = response.data_?.thread_personalized ?: emptyList()
                    PersonalizedPartialChange.Refresh.Success(
                        data = data.map { thread ->
                            val threadPersonalized =
                                threadPersonalizedData.firstOrNull { it.tid == thread.get { id } }
                                    ?.wrapImmutable()
                            ThreadItemData(thread = thread, personalized = threadPersonalized)
                        }.toImmutableList(),
                    )
                }
                .onStart { emit(PersonalizedPartialChange.Refresh.Start) }
                .catch { emit(PersonalizedPartialChange.Refresh.Failure(it)) }

        private fun PersonalizedUiIntent.LoadMore.producePartialChange(): Flow<PersonalizedPartialChange.LoadMore> =
            PersonalizedRepository
                .personalizedFlow(2, page)
                .map<PersonalizedResponse, PersonalizedPartialChange.LoadMore> { response ->
                    val data = response.toData()
                        .filter {
                            !App.INSTANCE.appPreferences.blockVideo || it.get { videoInfo } == null
                        }
                        .filter { it.get { ala_info } == null }
                        .let {
                            if (App.INSTANCE.appPreferences.personalizedFollowedOnly) filterFollowedOnly(it) else it
                        }
                    val threadPersonalizedData = response.data_?.thread_personalized ?: emptyList()
                    PersonalizedPartialChange.LoadMore.Success(
                        currentPage = page,
                        data = data.map { thread ->
                            val threadPersonalized =
                                threadPersonalizedData.firstOrNull { it.tid == thread.get { id } }
                                    ?.wrapImmutable()
                            ThreadItemData(thread = thread, personalized = threadPersonalized)
                        }.toImmutableList(),
                    )
                }
                .onStart { emit(PersonalizedPartialChange.LoadMore.Start) }
                .catch { emit(PersonalizedPartialChange.LoadMore.Failure(currentPage = page, error = it)) }

        private fun PersonalizedUiIntent.SetFollowMode.producePartialChange(): Flow<PersonalizedPartialChange.SetFollowMode> =
            flow<PersonalizedPartialChange.SetFollowMode> {
                App.INSTANCE.appPreferences.personalizedFollowedOnly = enabled
                // 切换模式即重拉首页：保持数据与当前模式一致（单次请求，避免重复调用）
                emit(PersonalizedPartialChange.SetFollowMode.Start(enabled))
                try {
                    val response = PersonalizedRepository.personalizedFlow(1, 1).first()
                    val threadPersonalizedData = response.data_?.thread_personalized ?: emptyList()
                    val data = response.toData()
                        .filter {
                            !App.INSTANCE.appPreferences.blockVideo || it.get { videoInfo } == null
                        }
                        .filter { it.get { ala_info } == null }
                        .let {
                            if (App.INSTANCE.appPreferences.personalizedFollowedOnly) filterFollowedOnly(it) else it
                        }
                    emit(
                        PersonalizedPartialChange.SetFollowMode.Success(
                            data = data.map { thread ->
                                val threadPersonalized =
                                    threadPersonalizedData.firstOrNull { it.tid == thread.get { id } }
                                        ?.wrapImmutable()
                                ThreadItemData(thread = thread, personalized = threadPersonalized)
                            }.toImmutableList(),
                            enabled = enabled
                        )
                    )
                } catch (e: Exception) {
                    emit(PersonalizedPartialChange.SetFollowMode.Failure(e))
                }
            }.flowOn(Dispatchers.IO)

        private fun PersonalizedUiIntent.Dislike.producePartialChange(): Flow<PersonalizedPartialChange.Dislike> =
            TiebaApi.getInstance().submitDislikeFlow(
                DislikeBean(
                    threadId.toString(),
                    reasons.joinToString(",") { it.get { dislikeId }.toString() },
                    forumId?.toString(),
                    clickTime,
                    reasons.joinToString(",") { it.get { extra } },
                )
            ).map<CommonResponse, PersonalizedPartialChange.Dislike> { PersonalizedPartialChange.Dislike.Success(threadId) }
                .catch { emit(PersonalizedPartialChange.Dislike.Failure(threadId, it)) }
                .onStart { emit(PersonalizedPartialChange.Dislike.Start(threadId)) }

        private fun PersonalizedUiIntent.Agree.producePartialChange(): Flow<PersonalizedPartialChange.Agree> =
            TiebaApi.getInstance()
                .opAgreeFlow(
                    threadId.toString(), postId.toString(), hasAgree, objType = 3
                )
                .map<AgreeBean, PersonalizedPartialChange.Agree> {
                    PersonalizedPartialChange.Agree.Success(
                        threadId,
                        hasAgree xor 1
                    )
                }
                .catch { emit(PersonalizedPartialChange.Agree.Failure(threadId, hasAgree, it)) }
                .onStart { emit(PersonalizedPartialChange.Agree.Start(threadId, hasAgree xor 1)) }

        private fun PersonalizedResponse.toData(): ImmutableList<ImmutableHolder<ThreadInfo>> {
            return (data_?.thread_list ?: emptyList()).wrapImmutable()
        }
    }
}

sealed interface PersonalizedUiIntent : UiIntent {
    data object Refresh : PersonalizedUiIntent

    data class LoadMore(val page: Int) : PersonalizedUiIntent

    /** 切换「只看已关注吧」模式并重拉首页 */
    data class SetFollowMode(val enabled: Boolean) : PersonalizedUiIntent

    data class Agree(
        val threadId: Long,
        val postId: Long,
        val hasAgree: Int
    ) : PersonalizedUiIntent

    data class Dislike(
        val forumId: Long?,
        val threadId: Long,
        val reasons: List<ImmutableHolder<DislikeReason>>,
        val clickTime: Long
    ) : PersonalizedUiIntent
}

sealed interface PersonalizedPartialChange : PartialChange<PersonalizedUiState> {
    sealed class Agree private constructor() : PersonalizedPartialChange {
        private fun List<ThreadItemData>.updateAgreeStatus(
            threadId: Long,
            hasAgree: Int,
        ): ImmutableList<ThreadItemData> {
            return map {
                val (threadInfo) = it.thread
                val newThreadInfo = if (threadInfo.threadId == threadId) {
                    if (threadInfo.agree != null) {
                        if (hasAgree != threadInfo.agree.hasAgree) {
                            if (hasAgree == 1) {
                                threadInfo.copy(
                                    agreeNum = threadInfo.agreeNum + 1,
                                    agree = threadInfo.agree.copy(
                                        agreeNum = threadInfo.agree.agreeNum + 1,
                                        diffAgreeNum = threadInfo.agree.diffAgreeNum + 1,
                                        hasAgree = 1
                                    )
                                )
                            } else {
                                threadInfo.copy(
                                    agreeNum = threadInfo.agreeNum - 1,
                                    agree = threadInfo.agree.copy(
                                        agreeNum = threadInfo.agree.agreeNum - 1,
                                        diffAgreeNum = threadInfo.agree.diffAgreeNum - 1,
                                        hasAgree = 0
                                    )
                                )
                            }
                        } else {
                            threadInfo
                        }
                    } else {
                        threadInfo.copy(
                            agreeNum = if (hasAgree == 1) threadInfo.agreeNum + 1 else threadInfo.agreeNum - 1
                        )
                    }
                } else {
                    threadInfo
                }
                it.copy(thread = newThreadInfo.wrapImmutable())
            }.toImmutableList()
        }

        override fun reduce(oldState: PersonalizedUiState): PersonalizedUiState =
            when (this) {
                is Start -> {
                    oldState.copy(data = oldState.data.updateAgreeStatus(threadId, hasAgree))
                }
                is Success -> {
                    oldState.copy(data = oldState.data.updateAgreeStatus(threadId, hasAgree))
                }
                is Failure -> {
                    oldState.copy(data = oldState.data.updateAgreeStatus(threadId, hasAgree))
                }
            }

        data class Start(
            val threadId: Long,
            val hasAgree: Int
        ) : Agree()

        data class Success(
            val threadId: Long,
            val hasAgree: Int
        ) : Agree()

        data class Failure(
            val threadId: Long,
            val hasAgree: Int,
            val error: Throwable
        ) : Agree()
    }

    sealed class Dislike private constructor() : PersonalizedPartialChange {
        override fun reduce(oldState: PersonalizedUiState): PersonalizedUiState =
            when (this) {
                is Start -> {
                    if (!oldState.hiddenThreadIds.contains(threadId)) {
                        oldState.copy(hiddenThreadIds = (oldState.hiddenThreadIds + threadId).toImmutableList())
                    } else {
                        oldState
                    }
                }
                is Success -> {
                    if (!oldState.hiddenThreadIds.contains(threadId)) {
                        oldState.copy(hiddenThreadIds = (oldState.hiddenThreadIds + threadId).toImmutableList())
                    } else {
                        oldState
                    }
                }
                is Failure -> oldState
            }

        data class Start(
            val threadId: Long,
        ) : Dislike()

        data class Success(
            val threadId: Long,
        ) : Dislike()

        data class Failure(
            val threadId: Long,
            val error: Throwable,
        ) : Dislike()
    }

    sealed class SetFollowMode private constructor() : PersonalizedPartialChange {
        override fun reduce(oldState: PersonalizedUiState): PersonalizedUiState =
            when (this) {
                is Start -> oldState.copy(
                    isRefreshing = true,
                    followedOnly = enabled,
                )
                is Success -> oldState.copy(
                    isRefreshing = false,
                    followedOnly = enabled,
                    currentPage = 1,
                    data = data.distinctById(),
                )
                is Failure -> oldState.copy(
                    isRefreshing = false,
                    error = error.wrapImmutable()
                )
            }

        data class Start(
            val enabled: Boolean,
        ) : SetFollowMode()

        data class Success(
            val data: List<ThreadItemData>,
            val enabled: Boolean,
        ) : SetFollowMode()

        data class Failure(
            val error: Throwable,
        ) : SetFollowMode()
    }

    sealed class Refresh private constructor() : PersonalizedPartialChange {
        override fun reduce(oldState: PersonalizedUiState): PersonalizedUiState =
            when (this) {
                Start -> oldState.copy(isRefreshing = true)
                is Success -> {
                    val oldSize = oldState.data.size
                    val newData = (data + oldState.data).distinctById()
                    oldState.copy(
                        isRefreshing = false,
                        currentPage = 1,
                        data = newData,
                        refreshPosition = if (oldState.data.isEmpty()) 0 else (newData.size - oldSize),
                    )
                }

                is Failure -> oldState.copy(
                    isRefreshing = false,
                    error = error.wrapImmutable()
                )
            }

        data object Start : Refresh()

        data class Success(
            val data: List<ThreadItemData>,
        ) : Refresh()

        data class Failure(
            val error: Throwable,
        ) : Refresh()
    }

    sealed class LoadMore private constructor() : PersonalizedPartialChange {
        override fun reduce(oldState: PersonalizedUiState): PersonalizedUiState =
            when (this) {
                Start -> oldState.copy(isLoadingMore = true)
                is Success -> oldState.copy(
                    isLoadingMore = false,
                    currentPage = currentPage,
                    data = (oldState.data + data).distinctById(),
                )

                is Failure -> oldState.copy(
                    isLoadingMore = false,
                    error = error.wrapImmutable()
                )
            }

        data object Start : LoadMore()

        data class Success(
            val currentPage: Int,
            val data: List<ThreadItemData>,
        ) : LoadMore()

        data class Failure(
            val currentPage: Int,
            val error: Throwable,
        ) : LoadMore()
    }
}

data class PersonalizedUiState(
    val isRefreshing: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: ImmutableHolder<Throwable>? = null,
    val currentPage: Int = 1,
    val data: ImmutableList<ThreadItemData> = persistentListOf(),
    val hiddenThreadIds: ImmutableList<Long> = persistentListOf(),
    val refreshPosition: Int = 0,
    /** 「只看已关注吧」模式 */
    val followedOnly: Boolean = false,
): UiState

sealed interface PersonalizedUiEvent : UiEvent {
    data class RefreshSuccess(val count: Int) : PersonalizedUiEvent
}