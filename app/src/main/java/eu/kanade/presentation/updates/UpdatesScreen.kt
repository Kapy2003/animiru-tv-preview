package eu.kanade.presentation.updates

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import eu.kanade.presentation.components.ChapterDownloadAction
import eu.kanade.presentation.components.EmptyScreen
import eu.kanade.presentation.components.FastScrollLazyColumn
import eu.kanade.presentation.components.LoadingScreen
import eu.kanade.presentation.components.MangaBottomActionMenu
import eu.kanade.presentation.components.PullRefresh
import eu.kanade.presentation.components.Scaffold
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.updates.manga.UpdatesItem
import eu.kanade.tachiyomi.ui.updates.manga.UpdatesState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@Composable
fun UpdateScreen(
    state: UpdatesState,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    lastUpdated: Long,
    relativeTime: Int,
    onClickCover: (UpdatesItem) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onInvertSelection: () -> Unit,
    onUpdateLibrary: () -> Boolean,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
    onMultiBookmarkClicked: (List<UpdatesItem>, bookmark: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<UpdatesItem>, read: Boolean) -> Unit,
    onMultiDeleteClicked: (List<UpdatesItem>) -> Unit,
    onUpdateSelected: (UpdatesItem, Boolean, Boolean, Boolean) -> Unit,
    onOpenChapter: (UpdatesItem) -> Unit,
) {
    BackHandler(enabled = state.selectionMode, onBack = { onSelectAll(false) })

    val context = LocalContext.current

    Scaffold(
        bottomBar = {
            UpdatesBottomBar(
                selected = state.selected,
                onDownloadChapter = onDownloadChapter,
                onMultiBookmarkClicked = onMultiBookmarkClicked,
                onMultiMarkAsReadClicked = onMultiMarkAsReadClicked,
                onMultiDeleteClicked = onMultiDeleteClicked,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) {
        when {
            state.isLoading -> LoadingScreen(modifier = Modifier.padding(contentPadding))
            state.items.isEmpty() -> EmptyScreen(
                textResource = R.string.information_no_recent,
                modifier = Modifier.padding(contentPadding),
            )
            else -> {
                val scope = rememberCoroutineScope()
                var isRefreshing by remember { mutableStateOf(false) }

                PullRefresh(
                    refreshing = isRefreshing,
                    onRefresh = {
                        val started = onUpdateLibrary()
                        if (!started) return@PullRefresh
                        scope.launch {
                            // Fake refresh status but hide it after a second as it's a long running task
                            isRefreshing = true
                            delay(1.seconds)
                            isRefreshing = false
                        }
                    },
                    enabled = !state.selectionMode,
                    indicatorPadding = contentPadding,
                ) {
                    FastScrollLazyColumn(
                        contentPadding = contentPadding,
                    ) {
                        if (lastUpdated > 0L) {
                            updatesLastUpdatedItem(lastUpdated)
                        }

                        updatesUiItems(
                            uiModels = state.getUiModel(context, relativeTime),
                            selectionMode = state.selectionMode,
                            onUpdateSelected = onUpdateSelected,
                            onClickCover = onClickCover,
                            onClickUpdate = onOpenChapter,
                            onDownloadChapter = onDownloadChapter,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdatesBottomBar(
    selected: List<UpdatesItem>,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
    onMultiBookmarkClicked: (List<UpdatesItem>, bookmark: Boolean) -> Unit,
    onMultiMarkAsReadClicked: (List<UpdatesItem>, read: Boolean) -> Unit,
    onMultiDeleteClicked: (List<UpdatesItem>) -> Unit,
) {
    MangaBottomActionMenu(
        visible = selected.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
        onBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected, true)
        }.takeIf { selected.fastAny { !it.update.bookmark } },
        onRemoveBookmarkClicked = {
            onMultiBookmarkClicked.invoke(selected, false)
        }.takeIf { selected.fastAll { it.update.bookmark } },
        onMarkAsReadClicked = {
            onMultiMarkAsReadClicked(selected, true)
        }.takeIf { selected.fastAny { !it.update.read } },
        onMarkAsUnreadClicked = {
            onMultiMarkAsReadClicked(selected, false)
        }.takeIf { selected.fastAny { it.update.read } },
        onDownloadClicked = {
            onDownloadChapter(selected, ChapterDownloadAction.START)
        }.takeIf {
            selected.fastAny { it.downloadStateProvider() != Download.State.DOWNLOADED }
        },
        onDeleteClicked = {
            onMultiDeleteClicked(selected)
        }.takeIf { selected.fastAny { it.downloadStateProvider() == Download.State.DOWNLOADED } },
    )
}

sealed class UpdatesUiModel {
    data class Header(val date: String) : UpdatesUiModel()
    data class Item(val item: UpdatesItem) : UpdatesUiModel()
}
