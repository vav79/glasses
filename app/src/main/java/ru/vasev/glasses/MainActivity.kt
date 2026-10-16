package ru.vasev.glasses

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.CompletableDeferred
import ru.vasev.glasses.GlassesApp.Companion.TAG
import ru.vasev.glasses.ui.MainScreen
import ru.vasev.glasses.ui.MainViewModel
import ru.vasev.glasses.ui.theme.GlassesTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var pendingMicAction: (() -> Unit)? = null
    private var pendingCameraPermission: CompletableDeferred<Boolean>? = null

    private val runtimePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val action = pendingMicAction
            pendingMicAction = null
            if (granted[Manifest.permission.RECORD_AUDIO] == true || hasMic()) action?.invoke()
        }

    private val cameraPermission =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            pendingCameraPermission?.complete(result.getOrNull() is PermissionStatus.Granted)
            pendingCameraPermission = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.cameraPermissionRequester = ::requestGlassesCamera
        handleRegistrationIntent(intent)
        handleDebugIntent(intent)
        requestRuntimePermissions()

        setContent {
            GlassesTheme {
                MainScreen(
                    viewModel = viewModel,
                    onAsk = { withMic(viewModel::ask) },
                    onWhatDoISee = { withMic(viewModel::whatDoISee) },
                    onConnectGlasses = { Wearables.startRegistration(this) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRegistrationIntent(intent)
        handleDebugIntent(intent)
    }

    override fun onDestroy() {
        viewModel.cameraPermissionRequester = null
        pendingCameraPermission?.complete(false)
        super.onDestroy()
    }

    /**
     * Debug: adb shell am start -n ru.vasev.glasses/.MainActivity --es question "Текст" [--ez with_frame true]
     */
    private fun handleDebugIntent(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val question = intent.getStringExtra("question") ?: return
        intent.removeExtra("question")
        viewModel.debugAsk(question, intent.getBooleanExtra("with_frame", false))
    }

    private fun handleRegistrationIntent(intent: Intent) {
        Wearables.handleIntent(intent) { request ->
            request.continueRegistration(this)
                .onFailure { error, _ -> Log.e(TAG, "Registration failed: ${error.description}") }
        }
    }

    private suspend fun requestGlassesCamera(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingCameraPermission?.complete(false)
        pendingCameraPermission = deferred
        cameraPermission.launch(Permission.CAMERA)
        return deferred.await()
    }

    private fun withMic(action: () -> Unit) {
        if (hasMic()) {
            action()
        } else {
            pendingMicAction = action
            runtimePermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun requestRuntimePermissions() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) runtimePermissions.launch(needed.toTypedArray())
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
