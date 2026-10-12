package com.goodyaoshi.lemonbox.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import java.util.Locale

/**
 * 语音录入（F13）：调用系统语音识别，把说出的内容转成文字回调给调用方。
 *
 * 返回一个「开始说话」的触发函数；识别结果通过 [onResult] 回吐。
 * 设备没有可用识别器时给出轻提示，不静默失败。
 */
@Composable
fun rememberVoiceInput(onResult: (String) -> Unit): () -> Unit {
    val appSnackbar = LocalAppSnackbar.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) onResult(text)
        }
    }
    return remember(launcher) {
        {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "说出你要找的东西")
            }
            try {
                launcher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                appSnackbar?.postMessage("这台设备没有可用的语音识别")
            }
        }
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
