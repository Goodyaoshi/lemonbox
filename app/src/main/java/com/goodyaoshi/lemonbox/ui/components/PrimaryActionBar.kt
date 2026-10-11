package com.goodyaoshi.lemonbox.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.goodyaoshi.lemonbox.ui.theme.TextHint

/**
 * 表单页统一的主操作条（P2）：主按钮 + 可选的一行说明。
 *
 * 改造前，「保存」这件事在四个表单页有四种版式：SaveScreen 是把按钮钉在底部、左右留 28dp；
 * EditScreen 是行内、下面挂一行灰字；RecordEdit / AnniversaryEdit 又各留各的间距。
 * 同一个人反复按同一个按钮，每次都长得不一样，会累积成「这个 App 有点乱」的印象。
 *
 * 这里把「按钮 + 说明 + 统一的左右/上下间距」收成一个组件，四个表单页共用：
 * 按钮本体仍走 [GradientButton]，说明文字走主题字阶，位置由调用方用 [modifier] 决定
 * （钉底、行内、是否带导航栏内边距等）。
 *
 * @param text 按钮文案（保存中可传入「保存中...」）
 * @param onClick 点击回调
 * @param enabled 是否可点，默认由调用方按表单校验结果传入
 * @param hint 按钮下方的一行说明，为 null 时不占位
 * @param horizontalPadding 左右内边距；表单自身已带内边距时可传 0，避免双重缩进
 * @param verticalPadding 上下内边距
 */
@Composable
fun PrimaryActionBar(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hint: String? = null,
    horizontalPadding: Dp = 20.dp,
    verticalPadding: Dp = 12.dp
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GradientButton(text = text, onClick = onClick, enabled = enabled)
        if (hint != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = TextHint,
                textAlign = TextAlign.Center
            )
        }
    }
}
