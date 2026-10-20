package com.goodyaoshi.lemonbox.ui.screen.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.goodyaoshi.lemonbox.data.local.entity.ItemDetail
import com.goodyaoshi.lemonbox.data.repository.WeeklyMealDay
import com.goodyaoshi.lemonbox.ui.components.AppDecorativeBackground
import com.goodyaoshi.lemonbox.ui.components.DeleteCopy
import com.goodyaoshi.lemonbox.ui.components.DividerLine
import com.goodyaoshi.lemonbox.ui.components.LocalAppSnackbar
import com.goodyaoshi.lemonbox.ui.components.AppSurfaceCard
import com.goodyaoshi.lemonbox.ui.components.EditorSelectionChip
import com.goodyaoshi.lemonbox.ui.components.LedgerBudgetBar
import com.goodyaoshi.lemonbox.ui.components.rememberBottomBarClearance
import com.goodyaoshi.lemonbox.ui.components.PillTag
import com.goodyaoshi.lemonbox.ui.components.SectionHeader
import com.goodyaoshi.lemonbox.ui.theme.Background
import com.goodyaoshi.lemonbox.ui.theme.CardWhite
import com.goodyaoshi.lemonbox.ui.theme.DividerSoft
import com.goodyaoshi.lemonbox.ui.theme.LemonEnd
import com.goodyaoshi.lemonbox.ui.theme.LemonStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeStart
import com.goodyaoshi.lemonbox.ui.theme.OrangeTint
import com.goodyaoshi.lemonbox.ui.theme.StatusExpired
import com.goodyaoshi.lemonbox.ui.theme.SurfaceWarm
import com.goodyaoshi.lemonbox.ui.theme.TagGreen
import com.goodyaoshi.lemonbox.ui.theme.TagGreenText
import com.goodyaoshi.lemonbox.ui.theme.TagOrangeText
import com.goodyaoshi.lemonbox.ui.theme.TextHint
import com.goodyaoshi.lemonbox.ui.theme.TextPrimary
import com.goodyaoshi.lemonbox.ui.theme.TextSecondary
import com.goodyaoshi.lemonbox.ui.viewmodel.AnniversaryRow
import com.goodyaoshi.lemonbox.ui.viewmodel.HomeViewModel
import com.goodyaoshi.lemonbox.ui.viewmodel.MonthSpending
import com.goodyaoshi.lemonbox.ui.viewmodel.TodayEvent
import com.goodyaoshi.lemonbox.ui.viewmodel.TodayEventKind
import com.goodyaoshi.lemonbox.ui.viewmodel.TodayEventTarget
import com.goodyaoshi.lemonbox.util.BrandCopy
import com.goodyaoshi.lemonbox.util.DateUtil
import com.goodyaoshi.lemonbox.util.LedgerMath
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 今日页只回答三件事：今天有什么要办的、今天吃什么、这个月花了多少。
 * 物品清单交给家当、一周菜单交给吃饭，这里只做跨域的「今天」聚合；
 * 搜索只留入口（IA-1），查找与筛选都收进家当 Tab。
 */
@Composable
fun HomeScreen(
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToEdit: (Long) -> Unit,
    onNavigateToExpiry: () -> Unit = {},
    onNavigateToScan: () -> Unit = {},
    onSearchInHousehold: (String) -> Unit = {},
    onNavigateToToBuy: () -> Unit = {},
    onNavigateToReminders: () -> Unit = {},
    onNavigateToLedger: () -> Unit = {},
    onNavigateToMeal: () -> Unit = {},
    onNavigateToAnniversaries: () -> Unit = {},
    // IA-3：首页纪念日条点某一行直达该条目的编辑，不必「我的 → 纪念日 → 编辑」三跳。
    onNavigateToAnniversaryEdit: (Long) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val todayEvents by viewModel.todayEvents.collectAsState()
    // G-11：被「今天忽略」的临期条单独收集，以灰字小行排在今日清单正常条目之后。
    val todayIgnoredEvents by viewModel.todayIgnoredEvents.collectAsState()
    val monthSpending by viewModel.monthSpending.collectAsState()
    val weekPlan by viewModel.weekPlan.collectAsState()
    val anniversaryRows by viewModel.anniversaryHighlights.collectAsState()
    val togetherDays by viewModel.togetherDays.collectAsState()
    val nickname by viewModel.nickname.collectAsState()
    val appSnackbar = LocalAppSnackbar.current
    val todayKey = remember { LocalDate.now().toString() }
    val todayMeal = remember(weekPlan, todayKey) {
        weekPlan.firstOrNull { it.dateKey == todayKey }
    }

    val onEventClick: (TodayEvent) -> Unit = { event ->
        when (val target = event.target) {
            TodayEventTarget.Reminders -> onNavigateToReminders()
            TodayEventTarget.Expiry -> onNavigateToExpiry()
            TodayEventTarget.ToBuy -> onNavigateToToBuy()
            is TodayEventTarget.ItemDetail -> onNavigateToDetail(target.itemId)
        }
    }

    Box {
        AppDecorativeBackground()

        Column(modifier = Modifier.fillMaxSize()) {
            HomeHeader(
                togetherLabel = togetherDays,
                nickname = nickname,
                eventCount = todayEvents.size
            )

            // 首页只留一个搜索入口：整条可点去家当，右侧扫码是同一身体里的附属动作。
            // 此前搜索和扫码各占一枚等重的浅绿胶囊，像两个并列的主按钮，反而不知道该点哪个。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(CircleShape)
                        .background(CardWhite)
                        .border(1.dp, DividerSoft, CircleShape)
                        .clickable(onClickLabel = "去家当搜索", role = Role.Button) {
                            onSearchInHousehold("")
                        }
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = OrangeStart,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "找东西、查库存",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextHint,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(20.dp)
                            .background(DividerSoft)
                    )
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onNavigateToScan),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "扫码",
                            tint = OrangeStart,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    bottom = rememberBottomBarClearance(extra = 16.dp)
                )
            ) {
                item {
                    TodaySection(
                        events = todayEvents,
                        ignoredEvents = todayIgnoredEvents,
                        // IA-1：今日清单是提醒的主数据源，这里给一个常驻的「管理提醒」出口，
                        // 不必再绕「我的 → 待办提醒」两跳去主动管理。
                        onManageReminders = onNavigateToReminders,
                        onEventClick = onEventClick,
                        onComplete = { event ->
                            // H1：勾掉前先拿快照，Snackbar 里可撤销。
                            viewModel.completeTodayEvent(event) { snapshot ->
                                if (snapshot != null) {
                                    appSnackbar?.showUndo(
                                        "已完成「${snapshot.title}」",
                                        DeleteCopy.UNDO_ACTION
                                    ) {
                                        viewModel.undoCompleteTodayEvent(snapshot)
                                    }
                                }
                            }
                        },
                        onIgnore = { event ->
                            // H1：临期事项「今天忽略」当天隐藏、明天照常回来，可撤销。
                            viewModel.ignoreExpiryToday(event)
                            appSnackbar?.showUndo(
                                "今天不再提醒「${event.title}」",
                                DeleteCopy.UNDO_ACTION
                            ) {
                                viewModel.undoIgnoreExpiryToday(event)
                            }
                        },
                        // G-11：撤销忽略后，条目自动回到上面的正常清单。
                        onUndoIgnore = { event -> viewModel.undoIgnoreExpiryToday(event) }
                    )
                }
                if (anniversaryRows.isNotEmpty()) {
                    item {
                        AnniversarySection(
                            rows = anniversaryRows,
                            onOpen = onNavigateToAnniversaries,
                            onOpenEdit = onNavigateToAnniversaryEdit
                        )
                    }
                }
                item {
                    TodayMealCard(
                        day = todayMeal,
                        // 做了的回执改为可撤销（GAP-12）：扣库存是隐性写操作，误触能一键回滚。
                        onCooked = {
                            viewModel.markDayCooked(todayKey) { snapshot ->
                                if (snapshot == null) {
                                    appSnackbar?.postMessage("今天这餐已经记过「做过」了")
                                } else {
                                    val consumed = snapshot.previousItems.size
                                    appSnackbar?.showUndo(
                                        if (consumed == 0) {
                                            "已记「做过」"
                                        } else {
                                            "已记「做过」，消耗了 $consumed 样食材"
                                        },
                                        DeleteCopy.UNDO_ACTION
                                    ) {
                                        viewModel.undoMarkDayCooked(snapshot)
                                    }
                                }
                            }
                        },
                        onGoPlan = onNavigateToMeal,
                        onDishItemClick = { onNavigateToDetail(it.item.id) }
                    )
                }
                monthSpending?.let { spending ->
                    item {
                        MonthSpendingBar(
                            spending = spending,
                            onClick = onNavigateToLedger
                        )
                    }
                }
            }
        }
    }
}

/**
 * 首页头部：把「今天过得怎么样」放在第一眼，而不是再报一遍产品名。
 *
 * 旧版是一整块高饱和柠檬黄，34sp 品牌名占掉首屏最贵的位置；用户每天打开看到的是
 * 自己已经知道的名字。现在品牌退成顶部一行小字，问候、日期和「第几天」成为主角，
 * 底色只留一层极浅的暖面与一束柠檬光，饱和黄只出现在小圆点与主按钮上。
 */
@Composable
private fun HomeHeader(
    togetherLabel: String?,
    nickname: String,
    eventCount: Int
) {
    val hour = rememberCurrentHour()
    val lateNight = isLateNight(hour)
    val greeting = "$nickname，${greetingFor(hour)}"
    val dateLabel = remember {
        val today = LocalDate.now()
        today.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)) + " " +
            today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
    }
    val subtitle = when {
        lateNight -> "都收好了，早点睡吧"
        eventCount > 0 -> "今天有 $eventCount 件事，一件件来"
        else -> "今天没有要紧事，慢慢过"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 形状走形状令牌：头部与页面底之间用 28dp 的下圆角收口。
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(Brush.verticalGradient(colors = listOf(SurfaceWarm, Background)))
            // 唯一的装饰：右上角一束柠檬光。不用渐变铺满整块 header。
            .drawBehind {
                val center = Offset(size.width * 0.94f, 0f)
                val radius = size.width * 0.72f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(LemonStart.copy(alpha = 0.20f), Color.Transparent),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 14.dp, bottom = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = BrandCopy.APP_NAME,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            // 只有你们懂的数字：建过累计型纪念日（如「在一起」）才会出现。
            if (togetherLabel != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(OrangeTint)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(LemonStart)
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = togetherLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = OrangeStart
                    )
                }
            }
        }
    }
}

/**
 * 当前小时数（F14）。原来用 `remember { … }` 只算一次，`remember` 只保证重组间不复算、并不会
 * 随时间失效，于是早上打开后一直显示「早上好」。这里用分钟级 tick 驱动：每跨过一分钟就比对一次，
 * 时段变了才更新状态、触发重组，避免无谓刷新。
 */
@Composable
private fun rememberCurrentHour(): Int {
    val hour by produceState(initialValue = LocalTime.now().hour) {
        while (true) {
            val now = LocalTime.now()
            // 对齐到下一分钟的 0 秒再刷新，避免固定 60s 累积漂移。
            delay((60 - now.second) * 1000L - now.nano / 1_000_000L)
            value = LocalTime.now().hour
        }
    }
    return hour
}

/** 23:00–05:00 视作深夜：问候与副文案都切到更安静的一档，App 深夜「说人话」。 */
private fun isLateNight(hour: Int): Boolean = hour >= 23 || hour <= 5

/** 按小时段落映射问候语，语义比 Calendar 取值更清晰；23 点并入「夜深了」。 */
private fun greetingFor(hour: Int): String = when (hour) {
    23, in 0..5 -> "夜深了"
    in 6..10 -> "早上好"
    in 11..13 -> "中午好"
    in 14..17 -> "下午好"
    else -> "晚上好"
}

/**
 * 「今天的事」：提醒待办 + 临期家当 + 待买的统一清单，空态一句话，能办的直接办掉（H1）：
 * 提醒给勾选圈（可撤销）、临期给「今天忽略」、待买给购物车直达，完成类操作都有 Snackbar 反馈。
 * 被「今天忽略」（G-11）的临期条以灰字小行排在正常条目之后，行尾可一键撤销忽略。
 */
@Composable
private fun TodaySection(
    events: List<TodayEvent>,
    ignoredEvents: List<TodayEvent>,
    onManageReminders: () -> Unit,
    onEventClick: (TodayEvent) -> Unit,
    onComplete: (TodayEvent) -> Unit,
    onIgnore: (TodayEvent) -> Unit,
    onUndoIgnore: (TodayEvent) -> Unit
) {
    // 事项可能很多（临期/待买一次涌进来几十条），首页只展示前几条，其余折叠起来，
    // 避免把首页撑得特别长；需要时展开看全部。
    var expanded by remember { mutableStateOf(false) }
    // 记住本次会话里是否出现过待办：用来区分「本来就没安排」与「刚刚把最后一件勾完」。
    var hadEvents by remember { mutableStateOf(false) }
    if (events.isNotEmpty()) hadEvents = true
    val justFinished = hadEvents && events.isEmpty()

    // 全部勾完的那一刻给一次 200ms 的轻微缩放，作为收尾的「小庆祝」。
    val finishScale = remember { Animatable(1f) }
    LaunchedEffect(justFinished) {
        if (justFinished) {
            finishScale.snapTo(0.96f)
            finishScale.animateTo(1f, tween(durationMillis = 200))
        }
    }

    // GAP-10：按类型微筛选，早上赶时间只想看「今天要买的」时不用肉眼分拣。
    var kindFilter by remember { mutableStateOf<TodayEventKind?>(null) }
    val shownEvents = if (kindFilter == null) events else events.filter { it.kind == kindFilter }

    val collapsible = shownEvents.size > TODAY_EVENT_PREVIEW_COUNT
    val visibleEvents = if (collapsible && !expanded) {
        shownEvents.take(TODAY_EVENT_PREVIEW_COUNT)
    } else {
        shownEvents
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        SectionHeader(
            title = "今天的事",
            // IA-1：空态与满态都给管理入口，主动管理不再只藏在「我的」二级页。
            // 文案取「待办」而非「提醒」：该入口跳的是「待办提醒」页，只管得着 chips 里的
            // 「提醒」一类（临期 / 待买各自有页面）。早先写「管理提醒」，与 chip 标签字面
            // 相同，容易让人以为点它能管理全部四类。
            action = "管理待办",
            onActionClick = onManageReminders
        )
        Spacer(modifier = Modifier.height(10.dp))
        if (events.isNotEmpty()) {
            // 类型 chips：数量为 0 的种类不出现（避免「待买 0」的噪音）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    null to "全部",
                    TodayEventKind.TASK to "提醒",
                    TodayEventKind.EXPIRY to "临期",
                    TodayEventKind.TO_BUY to "待买"
                ).forEach { (kind, label) ->
                    val count = if (kind == null) events.size else events.count { it.kind == kind }
                    if (kind != null && count == 0) return@forEach
                    EditorSelectionChip(
                        text = if (kind == null) label else "$label $count",
                        selected = kindFilter == kind,
                        onClick = { kindFilter = kind }
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        if (events.isEmpty()) {
            AppSurfaceCard(
                modifier = Modifier.graphicsLayer {
                    scaleX = finishScale.value
                    scaleY = finishScale.value
                },
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                containerColor = CardWhite,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(LemonEnd.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (justFinished) Icons.Outlined.CheckCircle else Icons.Default.TaskAlt,
                            contentDescription = null,
                            tint = LemonEnd,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (justFinished) "今天都办完了，去休息吧" else "今天没有要紧事",
                            // 字号走主题字阶（F6）：titleSmall 为 14sp。
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (justFinished) {
                                "剩下的时间，都是你的"
                            } else {
                                "待办提醒、临期家当和待买都会汇到这里"
                            },
                            // 字号走主题字阶（F6）：bodySmall 为 12sp。
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        } else if (shownEvents.isEmpty()) {
            // 选了某一类但该类刚好被清空（如刚把待买都办掉）：给一句提示，别让卡片毫无内容。
            Text(
                text = "这一类暂时没有事了",
                // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        } else {
            AppSurfaceCard(
                shape = MaterialTheme.shapes.large,
                contentPadding = PaddingValues(vertical = 4.dp),
                shadowElevation = 12.dp
            ) {
                visibleEvents.forEachIndexed { index, event ->
                    if (index > 0) {
                        DividerLine(modifier = Modifier.padding(horizontal = 14.dp))
                    }
                    TodayEventRow(
                        event = event,
                        onClick = { onEventClick(event) },
                        onComplete = { onComplete(event) },
                        onIgnore = { onIgnore(event) }
                    )
                }
                if (collapsible) {
                    DividerLine(modifier = Modifier.padding(horizontal = 14.dp))
                    TodayEventExpandRow(
                        hiddenCount = shownEvents.size - TODAY_EVENT_PREVIEW_COUNT,
                        expanded = expanded,
                        onToggle = { expanded = !expanded }
                    )
                }
            }
        }

        // G-11：被忽略的临期条以灰字小行垫在卡片下方——不抢正常清单的视觉权重，
        // 但让她第二天还能想起「是我自己今天先不理它的」，行尾「撤销」随时放回来。
        if (ignoredEvents.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            ignoredEvents.forEach { event ->
                TodayIgnoredRow(event = event, onUndo = { onUndoIgnore(event) })
            }
        }
    }
}

/** 「今天已忽略」灰条（G-11）：灰字小行弱化存在感，行尾「撤销」把条目放回今日清单。 */
@Composable
private fun TodayIgnoredRow(
    event: TodayEvent,
    onUndo: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.NotificationsOff,
            contentDescription = null,
            tint = TextHint,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "今天已忽略 · ${event.title}",
            // 字号走主题字阶（F6）：bodySmall 为 12sp。
            style = MaterialTheme.typography.bodySmall,
            // 承载可读正文（P2-3）：由 TextHint 换成 TextSecondary，对比度达标；
            // TextHint 只留给图标与占位。
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "撤销",
            // 字号走主题字阶（F6）：labelMedium 为 12sp。
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = OrangeStart,
            modifier = Modifier
                // 触达面积达标（F7）：热区对齐 48dp（视觉尺寸不变）。
                .minimumInteractiveComponentSize()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onUndo)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** 折叠/展开「今天的事」的剩余条目。 */
@Composable
private fun TodayEventExpandRow(
    hiddenCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    // 展开/收起用箭头方向表达状态，避免一个「›」在展开后仍指向右边。
    val arrowAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "todayExpandArrow"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (expanded) "收起" else "还有 $hiddenCount 件，展开查看",
            // 字号走主题字阶（F6）：labelLarge 为 14sp。
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = OrangeStart
        )
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = null,
            tint = OrangeStart,
            modifier = Modifier
                .size(18.dp)
                .rotate(arrowAngle)
                .padding(start = 2.dp)
        )
    }
}

/**
 * 单条今日事项：来源图标 + 标题/详情，点整行跳对应页面。
 * 尾部控件按类型分化（H1）：提醒待办给勾选圈、临期给「今天忽略」灰圈、待买给购物车直达。
 */
@Composable
private fun TodayEventRow(
    event: TodayEvent,
    onClick: () -> Unit,
    onComplete: () -> Unit,
    onIgnore: () -> Unit
) {
    val accent = when (event.kind) {
        TodayEventKind.TASK -> OrangeStart
        TodayEventKind.EXPIRY -> StatusExpired
        TodayEventKind.TO_BUY -> LemonEnd
    }
    val icon = when (event.kind) {
        TodayEventKind.TASK -> Icons.Default.Alarm
        TodayEventKind.EXPIRY -> Icons.Default.Notifications
        TodayEventKind.TO_BUY -> Icons.Default.ShoppingCart
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 一行一个图标就够：不再给每类事项套 36dp 的彩色方块，
        // 首页因此少掉三种色块，眼睛先读到的是事项本身。
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title,
                // 字号走主题字阶（F6）：titleSmall 为 14sp。
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            event.detail?.let { detail ->
                Text(
                    text = detail,
                    // 字号走主题字阶（F6）：bodySmall 为 12sp。
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
        when (event.kind) {
            TodayEventKind.TASK -> {
                if (event.completable) {
                    Box(
                        // 热区对齐 48dp（图标尺寸不变），便于点按完成。
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onComplete),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = "完成",
                            tint = TextHint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TextHint,
                    modifier = Modifier.size(16.dp)
                )
            }
            TodayEventKind.EXPIRY -> {
                // 「今天忽略」灰圈（H1）：点它当天不再出现在今日清单，行本体仍可进详情。
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onIgnore),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsOff,
                        contentDescription = "今天忽略",
                        tint = TextHint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TextHint,
                    modifier = Modifier.size(16.dp)
                )
            }
            TodayEventKind.TO_BUY -> {
                // 待买行尾的购物车与整行跳同一个目的地，属重复入口（P3-4）：去掉它，
                // 只留行本体这一个主入口；补右箭头与另外两类保持视觉一致。
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TextHint,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** 「纪念日」速览：最近的 1-2 条（倒数/生日在前，累计的随后），没有数据整块不占位。 */
@Composable
private fun AnniversarySection(
    rows: List<AnniversaryRow>,
    onOpen: () -> Unit,
    onOpenEdit: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        SectionHeader(
            title = "纪念日",
            action = "全部",
            onActionClick = onOpen,
            modifier = Modifier.padding(top = 8.dp, bottom = 10.dp)
        )
        AppSurfaceCard(
            shape = MaterialTheme.shapes.large,
            contentPadding = PaddingValues(vertical = 4.dp),
            shadowElevation = 12.dp
        ) {
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    DividerLine(modifier = Modifier.padding(horizontal = 14.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        // IA-3：点这一行直达该纪念日的编辑页，就地改名字/日期。
                        .clickable { onOpenEdit(row.anniversary.id) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = OrangeStart,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.name,
                            // 字号走主题字阶（F6）：titleSmall 为 14sp。
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = row.statusText,
                            // 字号走主题字阶（F6）：bodySmall 为 12sp。
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextHint,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/** 「今天吃什么」精简卡：今天的搭配 + 一键做了；细排、换一道去吃饭 Tab。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayMealCard(
    day: WeeklyMealDay?,
    onCooked: () -> Unit,
    onGoPlan: () -> Unit,
    onDishItemClick: (ItemDetail) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        SectionHeader(
            title = "今天吃什么",
            subtitle = "用现有食材安排，缺料自动进待买",
            // 卡片整体即主入口（P3-4）：去掉节标题里与之同目的地的重复「去安排 ›」。
            modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        AppSurfaceCard(
            shape = MaterialTheme.shapes.large,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            shadowElevation = 12.dp,
            onClick = onGoPlan
        ) {
            if (day == null) {
                Text(
                    text = "今天还没安排，去吃饭页看看吧",
                    // 字号走主题字阶（F6）：bodyMedium 为 14sp。
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = day.title,
                        // 字号走主题字阶（F6）：titleMedium 为 16sp。
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (day.cooked) {
                        PillTag(text = "已做", backgroundColor = TagGreen, contentColor = TagGreenText)
                    } else {
                        PillTag(
                            text = "做了",
                            backgroundColor = OrangeStart,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            onClick = onCooked
                        )
                    }
                }
                if (day.missing.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "还缺：${day.missing.joinToString("、")}",
                        // 字号走主题字阶（F6）：bodySmall 为 12sp。
                        style = MaterialTheme.typography.bodySmall,
                        color = TagOrangeText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val available = day.combo.dishes
                    .flatMap { it.suggestion?.available.orEmpty() }
                if (available.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        available.take(4).forEach { (label, detail) ->
                            PillTag(
                                text = label,
                                backgroundColor = TagGreen,
                                contentColor = TagGreenText,
                                onClick = { onDishItemClick(detail) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 「本月账」速览条：已花/预算一目了然，超支转警示色；未设预算只显示已花。 */
@Composable
private fun MonthSpendingBar(
    spending: MonthSpending,
    onClick: () -> Unit
) {
    val budget = spending.budgetCents
    val over = budget != null && budget > 0 && spending.spentCents >= budget
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        SectionHeader(
            title = "本月账",
            // 预算条整体即主入口（P3-4）：去掉节标题里与之同目的地的重复「去记账 ›」。
            modifier = Modifier.padding(top = 8.dp, bottom = 10.dp)
        )
        LedgerBudgetBar(
            title = when {
                over -> "本月预算已超支"
                budget != null && budget > 0 ->
                    "预算内，还剩 ${DateUtil.formatCurrency(LedgerMath.centsToYuan((budget - spending.spentCents).coerceAtLeast(0)))}"
                else -> "这个月已花 ${DateUtil.formatCurrency(LedgerMath.centsToYuan(spending.spentCents))}"
            },
            subtitle = buildString {
                append("已花 ${DateUtil.formatCurrency(LedgerMath.centsToYuan(spending.spentCents))}")
                if (budget != null && budget > 0) {
                    append(" · 预算 ${DateUtil.formatCurrency(LedgerMath.centsToYuan(budget))}")
                    if (!over) {
                        append(" · 还剩 ${DateUtil.formatCurrency(LedgerMath.centsToYuan((budget - spending.spentCents).coerceAtLeast(0)))}")
                    }
                }
            },
            ratio = if (budget != null && budget > 0) {
                spending.spentCents.toFloat() / budget
            } else {
                0f
            },
            over = over,
            onClick = onClick
        )
    }
}

/** 首页「今天的事」最多直接展示的条数，超出折叠。 */
private const val TODAY_EVENT_PREVIEW_COUNT = 5
