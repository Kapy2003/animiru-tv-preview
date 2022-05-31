package eu.kanade.tachiyomi.ui.browse.animeextension

import android.os.Bundle
import eu.kanade.domain.animeextension.interactor.GetAnimeExtensionLanguages
import eu.kanade.domain.animesource.interactor.ToggleLanguage
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.util.lang.launchIO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeExtensionFilterPresenter(
    private val getExtensionLanguages: GetAnimeExtensionLanguages = Injekt.get(),
    private val toggleLanguage: ToggleLanguage = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get(),
) : BasePresenter<AnimeExtensionFilterController>() {

    private val _state: MutableStateFlow<ExtensionFilterState> = MutableStateFlow(ExtensionFilterState.Loading)
    val state: StateFlow<ExtensionFilterState> = _state.asStateFlow()

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        presenterScope.launchIO {
            getExtensionLanguages.subscribe()
                .catch { exception ->
                    _state.value = ExtensionFilterState.Error(exception)
                }
                .collectLatest(::collectLatestSourceLangMap)
        }
    }

    private fun collectLatestSourceLangMap(extLangs: List<String>) {
        val enabledLanguages = preferences.enabledLanguages().get()
        val uiModels = extLangs.map {
            FilterUiModel(it, it in enabledLanguages)
        }
        _state.value = ExtensionFilterState.Success(uiModels)
    }

    fun toggleLanguage(language: String) {
        toggleLanguage.await(language)
    }
}

sealed class ExtensionFilterState {
    object Loading : ExtensionFilterState()
    data class Error(val error: Throwable) : ExtensionFilterState()
    data class Success(val models: List<FilterUiModel>) : ExtensionFilterState()
}
