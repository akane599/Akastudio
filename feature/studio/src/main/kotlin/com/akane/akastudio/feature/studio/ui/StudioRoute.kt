package com.akane.akastudio.feature.studio.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.di.CanvasHitTest
import com.akane.akastudio.feature.studio.domain.StudioDependencies
import com.akane.akastudio.feature.studio.mvi.StudioEffect
import com.akane.akastudio.feature.studio.mvi.StudioViewModel
import kotlinx.coroutines.launch

/**
 * The studio entry point: obtains the [StudioViewModel] for [container], renders [StudioScreen] from its state and
 * handles its one-shot effects (the share sheet and snackbar messages) while the screen is at least started.
 */
@Composable
fun StudioRoute(container: StudioDependencies, modifier: Modifier = Modifier) {
    val viewModel: StudioViewModel = viewModel(factory = studioViewModelFactory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val latestState by rememberUpdatedState(state)
    val composer = container.composer
    val hitTest: (Float, Float) -> Int? = remember(composer) {
        { x, y -> CanvasHitTest.slotAt(latestState, composer, x, y) }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is StudioEffect.ShareExport -> context.startActivity(shareChooser(context, effect))
                    // Each message queues in the host; showing one must not hold back the next effect.
                    is StudioEffect.ShowMessage -> launch {
                        snackbarHostState.showSnackbar(studioMessageText(context, effect.message))
                    }
                }
            }
        }
    }
    StudioScreen(
        state = state,
        onIntent = viewModel::onIntent,
        renderer = container.renderer,
        hitTest = hitTest,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/** A factory building the studio view model over [container]. */
fun studioViewModelFactory(container: StudioDependencies): ViewModelProvider.Factory = viewModelFactory {
    initializer { StudioViewModel(container) }
}

/** The system share sheet for an exported image, with read access to its content uri granted to the target. */
private fun shareChooser(context: Context, effect: StudioEffect.ShareExport): Intent {
    val uri = effect.uri.toUri()
    val send = Intent(Intent.ACTION_SEND).apply {
        type = effect.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, context.getString(R.string.studio_share_chooser))
}
