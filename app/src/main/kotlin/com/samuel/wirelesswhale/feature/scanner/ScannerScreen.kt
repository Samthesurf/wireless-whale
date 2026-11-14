package com.samuel.wirelesswhale.feature.scanner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.samuel.wirelesswhale.core.model.WifiCredentials
import com.samuel.wirelesswhale.qr.QrImageDecoder
import com.samuel.wirelesswhale.qr.WifiQrCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val ScannerTeal = Color(0xFF4DB6AC)
private val ScannerScrim = Color.Black.copy(alpha = 0.55f)
private val ScannerFrameBorder = Color.White
private const val VIEWFINDER_FRACTION = 0.72f

/**
 * Wi-Fi QR scanner. Owns the contextual camera permission, the CameraX
 * lifecycle, torch control, Storage Access Framework image import, and
 * decode-once handling.
 *
 * The result callback fires at most once per screen instance and never
 * auto-connects: the parent previews the credentials and drives the system
 * approval flow. All decoding is offline. Frames and payloads are never
 * logged. Image import uses OpenDocument, so no storage permission is
 * needed. The camera unbinds and the analysis executor shuts down when
 * this screen leaves the composition.
 */
@Composable
fun ScannerScreen(
  onResult: (WifiCredentials) -> Unit,
  onClose: () -> Unit
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()

  var hasPermission by remember { mutableStateOf(isCameraPermissionGranted(context)) }
  var permissionAsked by rememberSaveable { mutableStateOf(false) }
  var torchOn by remember { mutableStateOf(false) }
  var hasFlash by remember { mutableStateOf(false) }
  var cameraError by remember { mutableStateOf(false) }
  var statusMessage by remember { mutableStateOf<String?>(null) }
  var codeFound by remember { mutableStateOf(false) }
  var importing by remember { mutableStateOf(false) }

  val consumed = remember { AtomicBoolean(false) }
  val disposed = remember { AtomicBoolean(false) }
  val currentOnResult by rememberUpdatedState(onResult)
  val lastInvalidFeedback = remember { AtomicReference<String?>(null) }
  val cameraRef = remember { AtomicReference<Camera?>(null) }
  val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
  val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
  val imageAnalysis = remember {
    ImageAnalysis.Builder()
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
      .build()
  }
  val previewView = remember {
    PreviewView(context).apply {
      scaleType = PreviewView.ScaleType.FILL_CENTER
      contentDescription = "Camera preview"
    }
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    hasPermission = granted
    permissionAsked = true
    if (!granted) {
      statusMessage = "Camera access was denied. Scanning needs the camera on this screen only."
    }
  }

  val importLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument()
  ) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    if (!consumed.compareAndSet(false, true)) return@rememberLauncherForActivityResult
    importing = true
    scope.launch {
      val result = withContext(Dispatchers.IO) { importFromImage(context, uri) }
      importing = false
      if (result.isSuccess) {
        val credentials = result.getOrNull()!!
        val started = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (ScanResultGate.canDeliver(disposed.get(), started, alreadyConsumed = false)) {
          lastInvalidFeedback.set(null)
          codeFound = true
          currentOnResult(credentials)
        } else {
          // Paused or disposed host: never fire after close, and never
          // wedge the scanner consumed so resume can scan again.
          consumed.set(false)
        }
      } else {
        consumed.set(false)
        val reason = result.exceptionOrNull()?.message
          ?: "That image could not be read as a Wi-Fi QR code."
        lastInvalidFeedback.set(reason)
        if (!disposed.get() &&
          lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
          statusMessage = reason
        }
      }
    }
  }

  LaunchedEffect(hasPermission, lifecycleOwner) {
    if (!hasPermission) return@LaunchedEffect
    val provider = try {
      withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
    } catch (e: Exception) {
      cameraError = true
      statusMessage = "The camera could not be started on this device."
      return@LaunchedEffect
    }
    providerRef.set(provider)
    val preview = Preview.Builder().build()
    preview.setSurfaceProvider(previewView.surfaceProvider)
    imageAnalysis.setAnalyzer(analysisExecutor) { image ->
      try {
        if (disposed.get() || consumed.get()) return@setAnalyzer
        val text = QrImageDecoder.decodeImageProxy(image) ?: return@setAnalyzer
        val decoded = WifiQrCodec.decode(text)
        val credentials = decoded.getOrNull()
        if (credentials == null) {
          // A QR code was found but it is not a usable Wi-Fi code.
          // Show one deduplicated safe explanation (codec messages are
          // constants, never raw payloads) instead of silently ignoring
          // it or storming with toasts/vibration/logs.
          val reason = decoded.exceptionOrNull()?.message
            ?: "That QR code is not a Wi-Fi code."
          if (lastInvalidFeedback.getAndSet(reason) != reason) {
            scope.launch {
              if (!disposed.get() &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
              ) {
                statusMessage = reason
              }
            }
          }
          return@setAnalyzer
        }
        if (!consumed.compareAndSet(false, true)) return@setAnalyzer
        scope.launch {
          val started = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
          if (ScanResultGate.canDeliver(disposed.get(), started, alreadyConsumed = false)) {
            lastInvalidFeedback.set(null)
            imageAnalysis.clearAnalyzer()
            codeFound = true
            currentOnResult(credentials)
          } else {
            // Skipped while paused/disposed: reset so resume can scan.
            consumed.set(false)
          }
        }
      } finally {
        image.close()
      }
    }
    try {
      provider.unbindAll()
      val camera = provider.bindToLifecycle(
        lifecycleOwner,
        CameraSelector.DEFAULT_BACK_CAMERA,
        preview,
        imageAnalysis
      )
      cameraRef.set(camera)
      hasFlash = camera.cameraInfo.hasFlashUnit()
      torchOn = false
    } catch (e: Exception) {
      cameraError = true
      statusMessage = "The camera could not be started on this device."
    }
  }

  DisposableEffect(lifecycleOwner) {
    onDispose {
      disposed.set(true)
      runCatching { providerRef.get()?.unbindAll() }
      runCatching { imageAnalysis.clearAnalyzer() }
      analysisExecutor.shutdown()
    }
  }

  MaterialTheme(colorScheme = scannerColors()) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
      if (hasPermission) {
        AndroidView(
          factory = { previewView },
          modifier = Modifier.fillMaxSize()
        )
        if (!cameraError) {
          ViewfinderFrame(modifier = Modifier.fillMaxSize())
        }
      }

      Column(
        modifier = Modifier.fillMaxSize()
          .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
      ) {
        ScannerTopBar(onClose = onClose)
        Spacer(modifier = Modifier.weight(1f))
        ScannerStatus(
          cameraError = cameraError,
          codeFound = codeFound,
          importing = importing,
          statusMessage = statusMessage
        )
        ScannerControls(
          torchOn = torchOn,
          torchAvailable = hasPermission && hasFlash && !cameraError,
          importing = importing,
          onToggleTorch = {
            val camera = cameraRef.get() ?: return@ScannerControls
            val next = !torchOn
            runCatching { camera.cameraControl.enableTorch(next) }
            torchOn = next
          },
          onImport = { importLauncher.launch(arrayOf("image/*")) }
        )
      }

      if (!hasPermission) {
        CameraPermissionPrompt(
          permanentlyDenied = permissionAsked && !shouldShowCameraRationale(context),
          onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
          onOpenSettings = { openAppSettings(context) }
        )
      }
    }
  }
}

@Composable
private fun ScannerTopBar(onClose: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().statusBarsPadding()
      .padding(horizontal = 8.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    TextButton(onClick = onClose) {
      Text(text = "Close")
    }
    Text(
      text = "Scan Wi-Fi QR code",
      style = MaterialTheme.typography.titleMedium,
      color = Color.White,
      modifier = Modifier.weight(1f),
      textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.width(64.dp))
  }
}

@Composable
private fun ViewfinderFrame(modifier: Modifier = Modifier) {
  BoxWithConstraints(modifier = modifier) {
    val side = minOf(maxWidth, maxHeight) * VIEWFINDER_FRACTION
    val frameTop = ((maxHeight - side) / 2 - 24.dp).coerceAtLeast(0.dp)
    val frameLeft = ((maxWidth - side) / 2).coerceAtLeast(0.dp)
    val bottomHeight = (maxHeight - frameTop - side).coerceAtLeast(0.dp)
    Box(modifier = Modifier.fillMaxSize()) {
      Box(
        modifier = Modifier.fillMaxWidth().height(frameTop).background(ScannerScrim)
      )
      Box(
        modifier = Modifier.fillMaxWidth().height(bottomHeight)
          .align(Alignment.BottomCenter).background(ScannerScrim)
      )
      Box(
        modifier = Modifier.width(frameLeft).height(side)
          .align(Alignment.TopStart).offset(y = frameTop).background(ScannerScrim)
      )
      Box(
        modifier = Modifier.width(frameLeft).height(side)
          .align(Alignment.TopEnd).offset(y = frameTop).background(ScannerScrim)
      )
      Box(
        modifier = Modifier.size(side).align(Alignment.TopCenter).offset(y = frameTop)
          .border(2.dp, ScannerFrameBorder, RoundedCornerShape(20.dp))
      )
    }
  }
}

@Composable
private fun ScannerStatus(
  cameraError: Boolean,
  codeFound: Boolean,
  importing: Boolean,
  statusMessage: String?
) {
  val text = when {
    codeFound -> "Code found. Returning to the preview."
    cameraError -> statusMessage ?: "The camera could not be started on this device."
    importing -> "Reading the chosen image."
    statusMessage != null -> statusMessage
    else -> "Point the camera at a Wi-Fi QR code."
  }
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = Color.White,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth()
      .padding(horizontal = 24.dp, vertical = 8.dp)
      .semantics { liveRegion = LiveRegionMode.Polite }
  )
}

@Composable
private fun ScannerControls(
  torchOn: Boolean,
  torchAvailable: Boolean,
  importing: Boolean,
  onToggleTorch: () -> Unit,
  onImport: () -> Unit
) {
  // Narrow widths (320dp) and large font scales (2.0) clip a fixed Row,
  // so fall back to a Column. No font-scale cap: layout adapts instead.
  // Bottom safe-drawing insets keep primary controls above system bars.
  BoxWithConstraints(
    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
      .padding(horizontal = 24.dp, vertical = 20.dp)
  ) {
    if (maxWidth < 360.dp) {
      Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
      ) {
        ScannerControlButtons(
          torchOn = torchOn,
          torchAvailable = torchAvailable,
          importing = importing,
          onToggleTorch = onToggleTorch,
          onImport = onImport,
          fullWidth = true
        )
      }
    } else {
      Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
      ) {
        ScannerControlButtons(
          torchOn = torchOn,
          torchAvailable = torchAvailable,
          importing = importing,
          onToggleTorch = onToggleTorch,
          onImport = onImport,
          fullWidth = false
        )
      }
    }
  }
}

@Composable
private fun ScannerControlButtons(
  torchOn: Boolean,
  torchAvailable: Boolean,
  importing: Boolean,
  onToggleTorch: () -> Unit,
  onImport: () -> Unit,
  fullWidth: Boolean
) {
  val buttonModifier = if (fullWidth) Modifier.fillMaxWidth() else Modifier
  Button(
    onClick = onToggleTorch,
    enabled = torchAvailable,
    modifier = buttonModifier,
    colors = ButtonDefaults.buttonColors(
      containerColor = ScannerTeal,
      contentColor = Color.Black,
      disabledContainerColor = Color.DarkGray,
      disabledContentColor = Color.LightGray
    )
  ) {
    Text(text = if (torchOn) "Torch off" else "Torch on")
  }
  Button(
    onClick = onImport,
    enabled = !importing,
    modifier = buttonModifier,
    colors = ButtonDefaults.buttonColors(
      containerColor = Color.White,
      contentColor = Color.Black,
      disabledContainerColor = Color.DarkGray,
      disabledContentColor = Color.LightGray
    )
  ) {
    Text(text = if (importing) "Reading" else "Choose image")
  }
}

@Composable
private fun CameraPermissionPrompt(
  permanentlyDenied: Boolean,
  onGrant: () -> Unit,
  onOpenSettings: () -> Unit
) {
  Box(
    modifier = Modifier.fillMaxSize().background(ScannerScrim).padding(24.dp),
    contentAlignment = Alignment.Center
  ) {
    Card(
      colors = CardDefaults.cardColors(
        containerColor = Color(0xFF101414),
        contentColor = Color.White
      )
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text(
          text = "Camera access needed",
          style = MaterialTheme.typography.titleMedium
        )
        Text(
          text = "Wireless Whale scans QR codes with the camera on this screen only. " +
            "Frames are decoded on this device and never uploaded.",
          style = MaterialTheme.typography.bodyMedium
        )
        Button(
          onClick = onGrant,
          colors = ButtonDefaults.buttonColors(
            containerColor = ScannerTeal,
            contentColor = Color.Black
          )
        ) {
          Text(text = "Allow camera")
        }
        if (permanentlyDenied) {
          Text(
            text = "Camera access is blocked. Open the app settings to allow it.",
            style = MaterialTheme.typography.bodySmall
          )
          TextButton(onClick = onOpenSettings) {
            Text(text = "Open settings", color = ScannerTeal)
          }
        }
      }
    }
  }
}

private fun scannerColors() = darkColorScheme(
  primary = ScannerTeal,
  onPrimary = Color.Black,
  surface = Color(0xFF101414),
  onSurface = Color.White,
  background = Color.Black,
  onBackground = Color.White,
  error = Color(0xFFFF8A80),
  onError = Color.Black
)

private fun isCameraPermissionGranted(context: Context): Boolean {
  return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
    PackageManager.PERMISSION_GRANTED
}

private fun shouldShowCameraRationale(context: Context): Boolean {
  val activity = context as? Activity ?: return true
  return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
}

private fun openAppSettings(context: Context) {
  val intent = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.fromParts("package", context.packageName, null)
  ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  runCatching { context.startActivity(intent) }
}

private fun importFromImage(context: Context, uri: Uri): Result<WifiCredentials> {
  val bitmap = QrImageDecoder.decodeContentImage(context.contentResolver, uri)
    ?: return Result.failure(IllegalArgumentException("That image could not be read."))
  val text = QrImageDecoder.decodeBitmap(bitmap)
    ?: return Result.failure(IllegalArgumentException("No QR code was found in that image."))
  return WifiQrCodec.decode(text)
}
