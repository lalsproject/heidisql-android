package app.heidimobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.heidimobile.ui.BrowserScreen
import app.heidimobile.ui.EditScreen
import app.heidimobile.ui.ErrorDialog
import app.heidimobile.ui.SessionsScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HeidiTheme { Root(vm) } }
    }
}

@Composable
private fun HeidiTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF90CAF9))
    else lightColorScheme(primary = Color(0xFF1565C0))
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun Root(vm: AppViewModel) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.notice) {
        vm.notice?.let {
            snack.showSnackbar(it)
            vm.notice = null
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when (val s = vm.screen) {
                Screen.Sessions -> SessionsScreen(vm)
                is Screen.Edit -> EditScreen(vm, s.session)
                Screen.Browser -> BrowserScreen(vm)
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
        }
    }
    vm.error?.let { ErrorDialog(it) { vm.error = null } }
}
