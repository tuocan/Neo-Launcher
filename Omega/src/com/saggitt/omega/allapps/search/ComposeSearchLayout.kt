/*
 * This file is part of Neo Launcher
 * Copyright (c) 2023 Neo Launcher Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.saggitt.omega.allapps.search

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.android.launcher3.ExtendedEditText
import com.android.launcher3.Insettable
import com.android.launcher3.R
import com.android.launcher3.ResourceUtils
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.allapps.SearchUiManager
import com.android.launcher3.allapps.search.AllAppsSearchBarController
import com.android.launcher3.graphics.IconShape
import com.android.launcher3.search.SearchCallback
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.saggitt.omega.compose.icons.Phosphor
import com.saggitt.omega.compose.icons.phosphor.Nut
import com.saggitt.omega.compose.icons.phosphor.X
import com.saggitt.omega.compose.navigation.Routes
import com.saggitt.omega.nLauncher
import com.saggitt.omega.preferences.NeoPrefs
import com.saggitt.omega.preferences.PreferenceActivity
import com.saggitt.omega.search.NeoAppSearchAlgorithm
import com.saggitt.omega.search.SearchProvider
import com.saggitt.omega.search.SearchProviderController
import com.saggitt.omega.search.WebSearchProvider
import com.saggitt.omega.theme.OmegaAppTheme
import com.saggitt.omega.util.prefs
import kotlin.math.round

open class ComposeSearchLayout(context: Context, attrs: AttributeSet? = null) :
    AbstractComposeView(context, attrs), SearchUiManager, Insettable,
    SearchCallback<BaseAllAppsAdapter.AdapterItem>,
    SearchProviderController.OnProviderChangeListener {
    //SharedPreferences.OnSharedPreferenceChangeListener {
    var mContext: Context = context
    protected var prefs: NeoPrefs = mContext.prefs
    protected var spController = SearchProviderController.getInstance(getContext())
    private var searchProvider: SearchProvider = spController.searchProvider
    private val searchAlgorithm = NeoAppSearchAlgorithm(mContext)
    private val mSearchBarController: AllAppsSearchBarController = AllAppsSearchBarController()

    private var mAppsView: ActivityAllAppsContainerView<*>? = null

    private lateinit var focusManager: FocusManager
    private var keyboardController: SoftwareKeyboardController? = null
    val query = mutableStateOf("")

    @Composable
    override fun Content() {
        OmegaAppTheme {
            focusManager = LocalFocusManager.current
            keyboardController = LocalSoftwareKeyboardController.current
            val textFieldFocusRequester = remember { FocusRequester() }
            val searchIcon = rememberDrawablePainter(drawable = searchProvider.icon)
            val micIcon = rememberDrawablePainter(
                drawable = if (searchProvider.supportsAssistant) searchProvider.assistantIcon
                else searchProvider.voiceIcon
            )

            var textFieldValue by query

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = textFieldValue,
                    onValueChange = {
                        textFieldValue = it
                        onQueryChanged(it)
                    },
                    modifier = Modifier
                        .weight(1f, true)
                        .focusRequester(textFieldFocusRequester),
                    /*.onFocusChanged {
                        if (it.isFocused) {
                            context.startActivity(
                                PreferenceActivity.createIntent(
                                    context,
                                    "${Routes.PREFS_SEARCH}/"
                                )
                            )
                        } else {

                        }
                    }*/
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    shape = RoundedCornerShape(getCornerRadius().dp),
                    leadingIcon = {
                        Icon(
                            modifier = Modifier.size(24.dp),
                            painter = searchIcon,
                            contentDescription = stringResource(id = R.string.label_search),
                        )
                    },
                    trailingIcon = {
                        Row {
                            AnimatedVisibility(visible = textFieldValue.isNotEmpty()) {
                                IconButton(onClick = {
                                    query.value = ""
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    resetSearch()
                                }) {
                                    Icon(
                                        imageVector = Phosphor.X,
                                        contentDescription = stringResource(id = R.string.widgets_full_sheet_cancel_button_description),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            AnimatedVisibility(visible = searchProvider.supportsVoiceSearch) {
                                IconButton(onClick = {
                                    if (searchProvider.supportsAssistant) {
                                        searchProvider.startAssistant { intent ->
                                            mContext.startActivity(intent)
                                        }
                                    } else {
                                        searchProvider.startVoiceSearch { intent ->
                                            mContext.startActivity(intent)
                                        }
                                    }
                                }) {
                                    Icon(
                                        modifier = Modifier.size(24.dp),
                                        painter = micIcon,
                                        contentDescription = stringResource(id = R.string.label_voice_search),
                                    )
                                }
                            }
                        }
                    },
                    label = { Text(text = stringResource(id = R.string.all_apps_search_bar_hint)) },
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = { mContext.nLauncher.appsView.mainAdapterProvider.launchHighlightedItem() },
                    ),
                )
                IconButton(onClick = {
                    context.startActivity(
                        PreferenceActivity.createIntent(
                            context,
                            "${Routes.PREFS_SEARCH}/"
                        )
                    )
                }) {
                    Icon(
                        imageVector = Phosphor.Nut,
                        contentDescription = stringResource(id = R.string.widgets_full_sheet_cancel_button_description),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

        }
    }

    private fun getCornerRadius(): Float {
        val defaultRadius = ResourceUtils.pxFromDp(100f, resources.displayMetrics).toFloat()
        val radius: Float = round(prefs.searchBarRadius.getValue())
        if (radius >= 0f) {
            return radius
        }
        val edgeRadius: TypedValue? = IconShape.getShape().getAttrValue(R.attr.qsbEdgeRadius)
        return edgeRadius?.getDimension(context.resources.displayMetrics) ?: defaultRadius
    }

    private fun onQueryChanged(query: String) {
        if (query.isEmpty()) {
            searchAlgorithm.cancel(true)
            clearSearchResult()
        } else {
            searchAlgorithm.cancel(false)
            searchAlgorithm.doSearch(query, this)
        }
    }


    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SearchProviderController.getInstance(mContext).addOnProviderChangeListener(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        SearchProviderController.getInstance(mContext).removeOnProviderChangeListener(this)
    }

    override fun onSearchProviderChanged() {
        searchProvider = spController.searchProvider
    }

    override fun initializeSearch(containerView: ActivityAllAppsContainerView<*>?) {
        mAppsView = containerView
        mSearchBarController.initialize(
            NeoAppSearchAlgorithm(mContext.nLauncher),
            null, /*mCancelButton,*/ mContext.nLauncher, this
        )
    }

    override fun resetSearch() {
        clearSearchResult()
    }

    override fun clearSearchResult() {
        mAppsView?.setSearchResults(null)
        query.value = ""
        mAppsView?.onClearSearchResult()
    }

    override fun startSearch() {
        startSearch("")
    }

    fun startSearch(searchQuery: String) {
        query.value = searchQuery.trim()
    }

    override fun getEditText(): ExtendedEditText? = null

    override fun hideSoftwareKeyboard() {
        keyboardController?.hide()
    }

    override fun onSearchResult(
        query: String?,
        items: ArrayList<BaseAllAppsAdapter.AdapterItem>?,
        suggestions: MutableList<String>?,
    ) {
        if (items != null) {
            mAppsView?.setSearchResults(items)
        }
    }

    override fun onSubmitSearch(query: String?): Boolean =
        if (searchProvider is WebSearchProvider) {
            (searchProvider as WebSearchProvider).openResults(query!!)
            true
        } else false

    override fun setInsets(insets: Rect) {
        val mlp = layoutParams as MarginLayoutParams
        mlp.topMargin = insets.top
        requestLayout()
    }
}