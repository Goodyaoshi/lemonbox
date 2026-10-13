package com.goodyaoshi.lemonbox.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OnLemon
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.ScrimSoft
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarm
import com.goodyaoshi.lemonbox.ui.theme.TagRedText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import java.util.Locale

/**
 * 语音录入（F13）：不再跳系统识别页，改为应用内自绘的录音弹窗。
 *
 * 流程：点麦克风 → 按需申请麦克风权限 → 弹出录音弹窗并直接开始聆听，
 * 识别中途的临时文字实时上屏，说完点「说完了」或等自动结束，结果回调给调用方。
 * 设备没有可用识别器时给出轻提示，不静默失败。
 */
@Composable
fun rememberVoiceInput(onResult: (String) -> Unit): () -> Unit {
    val appSnackbar = LocalAppSnackbar.current
    val context = LocalContext.current
    var showRecorder by remember { mutableStateOf(false) }
    val currentOnResult by rememberUpdatedState(onResult)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showRecorder = true
        } else {
            appSnackbar?.postMessage("需要麦克风权限才能语音输入")
        }
    }

    if (showRecorder) {
        VoiceRecordDialog(
            onResult = { spoken ->
                showRecorder = false
                currentOnResult(spoken)
            },
            onDismiss = { showRecorder = false }
        )
    }

    return {
        when {
            !hasRecognitionService(context) ->
                appSnackbar?.postMessage("这台设备没有可用的语音识别")
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> showRecorder = true
            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** 设备是否装有语音识别服务（与 isRecognitionAvailable 同判据，规避其弃用标记）。 */
private fun hasRecognitionService(context: Context): Boolean =
    context.packageManager.queryIntentServices(
        Intent(RecognitionService.SERVICE_INTERFACE), 0
    ).isNotEmpty()

/**
 * 应用内录音弹窗：柠檬渐变的呼吸麦克风 + 实时上屏的临时文字，
 * 出错给友好文案与「再试一次」，全程可取消。
 */
@Composable
private fun VoiceRecordDialog(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val recognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    var listening by remember { mutableStateOf(true) }
    var partial by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val startListening = {
        partial = ""
        error = null
        listening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.startListening(intent)
    }

    DisposableEffect(Unit) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(errorCode: Int) {
                listening = false
                error = when (errorCode) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清，靠近一点再说一次"
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别需要联网，请检查网络"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
                    SpeechRecognizer.ERROR_CLIENT ->
                        "设备语音服务缺少识别资源，可在系统设置里检查语音引擎，或联网后重试"
                    else -> "识别出错了，请再试一次"
                }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (text.isNullOrBlank()) {
                    error = "没听清，靠近一点再说一次"
                } else {
                    onResult(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.lastOrNull()
                    .orEmpty()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        startListening()
        onDispose { recognizer.destroy() }
    }

    // 聆听时的呼吸动画：主体轻微缩放 + 外圈光晕同步呼吸；出错后静止。
    val breath = rememberInfiniteTransition(label = "voiceBreath")
    val breathScale by breath.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "voiceScale"
    )
    val breathGlow by breath.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "voiceGlow"
    )
    val micScale = if (listening && error == null) breathScale else 1f
    val glowAlpha = if (listening && error == null) breathGlow else 0.12f

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ScrimSoft),
            contentAlignment = Alignment.Center
        ) {
            AppSurfaceCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .widthIn(max = 420.dp),
                shape = RoundedCornerShape(30.dp),
                contentPadding = PaddingValues(24.dp),
                shadowElevation = 26.dp
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "语音搜索",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Box(
                        modifier = Modifier.padding(top = 22.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // 外圈光晕
                        Box(
                            modifier = Modifier
                                .size(124.dp)
                                .scale(micScale)
                                .clip(CircleShape)
                                .background(LemonStart.copy(alpha = glowAlpha))
                        )
                        // 渐变主体麦克风
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .scale(micScale)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(LemonStart, LemonEnd))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "正在聆听",
                                tint = OnLemon,
                                modifier = Modifier.size(38.dp)
                            )
                        }
                    }

                    Text(
                        text = if (error != null) error!! else if (listening) "正在聆听…" else "正在整理…",
                        fontSize = 14.sp,
                        color = if (error != null) TagRedText else TextSecondary,
                        modifier = Modifier.padding(top = 18.dp)
                    )
                    // 固定最小高度放临时文字，避免识别中文案长短不一引起跳动。
                    Text(
                        text = partial.ifBlank { "开口说话，这里会实时显示你说的内容" },
                        fontSize = 15.sp,
                        color = if (partial.isBlank()) TextHint else TextPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .padding(top = 8.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        VoiceDialogSoftButton(
                            label = "取消",
                            modifier = Modifier.weight(1f),
                            onClick = onDismiss
                        )
                        if (error != null) {
                            VoiceDialogPrimaryButton(
                                label = "再试一次",
                                modifier = Modifier.weight(1f),
                                onClick = startListening
                            )
                        } else {
                            VoiceDialogPrimaryButton(
                                label = "说完了",
                                modifier = Modifier.weight(1f),
                                onClick = { recognizer.stopListening() }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 弹窗次按钮：暖色弱化底。 */
@Composable
private fun VoiceDialogSoftButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(SurfaceWarm)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, fontSize = 15.sp, color = TextSecondary)
    }
}

/** 弹窗主按钮：柠檬渐变胶囊。 */
@Composable
private fun VoiceDialogPrimaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(LemonStart, LemonEnd)))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, fontSize = 15.sp, color = OnLemon, fontWeight = FontWeight.Bold)
    }
}

/** 麦克风按钮：与搜索框同一行的圆形入口。 */
@Composable
fun VoiceInputButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(CardWhite.copy(alpha = 0.96f))
            .border(1.dp, DividerSoft, RoundedCornerShape(26.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "语音输入",
            tint = OrangeStart,
            modifier = Modifier.size(22.dp)
        )
    }
}
