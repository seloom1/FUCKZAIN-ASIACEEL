package com.seloom.vpn

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import kotlin.random.Random
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import kotlin.math.hypot
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF020207); private val Panel = Color(0xFF16091F)
private val Red = Color(0xFFFF2638); private val DeepRed = Color(0xFF6B0A14)
private val Green = Color(0xFF00FFB3); private val Blue = Color(0xFF6B7CFF)
private val Dim = Color(0xFFB8AFC2); private val TextPrimary = Color(0xFFF8F4FF)
private val Cut = CutCornerShape(14.dp)
private data class InstalledApp(val packageName: String, val label: String, val icon: Drawable)

private fun Modifier.panel() = this.background(Panel, Cut)
    .border(1.dp, Brush.horizontalGradient(listOf(Red.copy(0.8f), DeepRed, Red.copy(0.8f))), Cut)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Vpn.load(this)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = darkColorScheme(primary = Red, background = Ink, surface = Panel, onBackground = TextPrimary, onSurface = TextPrimary)) { App() }
            }
        }
    }

}

@Composable
fun App() {
    val ctx = LocalContext.current
    val st by Vpn.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val permit = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) Vpn.start(ctx)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun toggle() {
        if (st.connected || st.connecting) Vpn.stop(ctx)
        else VpnService.prepare(ctx)?.let { permit.launch(it) } ?: Vpn.start(ctx)
    }
    Box(Modifier.fillMaxSize().background(Ink).drawBehind { drawScene() }.statusBarsPadding().navigationBarsPadding()) {
        Image(painterResource(R.drawable.dashboard_bg), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.45f)
        Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.55f)))
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> Home(st, ::toggle, onSettings = { tab = 2 }, onProtocol = { tab = 3 })
                    1 -> RtlBox { SpeedScreen(st) }
                    2 -> RtlBox { SettingsScreen(st) }
                    else -> RtlBox { Links(st) }
                }
            }
            NavBar(tab) { tab = it }
            Footer()
        }
    }
}

@Composable
fun RtlBox(c: @Composable () -> Unit) = CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { c() }

/** الخلفية: شبكة نقاط حمراء، برج، خطوط زوايا */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScene() {
    val w = size.width; val h = size.height
    drawRect(Brush.radialGradient(listOf(Color(0x88FF2638), Color(0x221B5CFF), Color.Transparent), Offset(w * 0.50f, h * 0.34f), w * 0.95f))
    drawRect(Brush.radialGradient(listOf(Color(0x553B21FF), Color.Transparent), Offset(w * 0.08f, h * 0.82f), w * 0.62f))
    val r = Random(7)
    val pts = List(30) { Offset(r.nextFloat() * w * 0.42f, r.nextFloat() * h * 0.26f) } +
        List(46) { Offset(w * (0.58f + r.nextFloat() * 0.42f), r.nextFloat() * h * 0.28f) }
    for (a in pts) for (b in pts) if (a != b && hypot(a.x - b.x, a.y - b.y) < w * 0.16f)
        drawLine(Red.copy(0.22f), a, b, 1f)
    pts.forEach { drawCircle(Red.copy(0.8f), 2.2f, it) }
    val tx = w * 0.09f
    drawLine(Red.copy(0.7f), Offset(tx, h * 0.145f), Offset(tx - 22f, h * 0.27f), 2f)
    drawLine(Red.copy(0.7f), Offset(tx, h * 0.145f), Offset(tx + 22f, h * 0.27f), 2f)
    drawCircle(Red, 6f, Offset(tx, h * 0.145f))
    listOf(20f, 38f, 56f).forEach { drawCircle(Red.copy(0.35f), it, Offset(tx, h * 0.145f), style = Stroke(1.5f)) }
    val edge = Red.copy(0.55f)
    listOf(0f to 1f, 1f to -1f).forEach { (x0, d) ->
        val x = w * x0
        drawLine(edge, Offset(x, h * 0.03f), Offset(x + d * w * 0.06f, 0f), 3f)
        drawLine(edge, Offset(x, h * 0.62f), Offset(x + d * w * 0.16f, h * 0.53f), 2f)
        drawLine(edge.copy(0.3f), Offset(x, h * 0.66f), Offset(x + d * w * 0.20f, h * 0.55f), 1.5f)
    }
}

@Composable
fun Home(st: UiState, onToggle: () -> Unit, onSettings: () -> Unit, onProtocol: () -> Unit) {
    var showDeveloper by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(Icons.Filled.Menu, "معلومات المطور", tint = Color.White,
                modifier = Modifier.size(28.dp).clickable { showDeveloper = true })
            Icon(Icons.Filled.Settings, null, tint = Color.White,
                modifier = Modifier.size(28.dp).clickable(onClick = onSettings))
        }
        Hero()
        Spacer(Modifier.height(5.dp))
        Text("FUCK ASIACEEL & ZAIN", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Black,
            letterSpacing = 0.7.sp, modifier = Modifier.align(Alignment.CenterHorizontally), maxLines = 1)
        Spacer(Modifier.height(8.dp))
        ExitIpCard(st)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().panel().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Flag(st.countryCode)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("السيرفر الحالي", color = Dim, fontSize = 10.sp)
                Text(st.current?.name ?: "اختر سيرفراً من القائمة", color = TextPrimary,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Box(Modifier.width(1.dp).height(32.dp).background(DeepRed))
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.CellTower, null, tint = Red, modifier = Modifier.size(18.dp))
                Text("${st.pingMs.takeIf { it >= 0 }?.let { "$it ms" } ?: "—"}",
                    color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
            PowerButton(st, onToggle)
        }
        Text(if (st.connected) "الاتصال آمن ونشط" else if (st.connecting) "جارٍ إنشاء الاتصال…" else "اضغط للاتصال",
            color = if (st.connected) Green else Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(9.dp))
        Column(Modifier.fillMaxWidth().panel().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("حركة الإنترنت المباشرة", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (st.connected) Green else Dim, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(if (st.connected) "مباشر" else "غير متصل", color = Dim, fontSize = 9.sp)
                }
            }
            HeartbeatWave(st.connected, st.upBps, st.downBps)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeedStat(Modifier.weight(1f), Icons.Filled.ArrowUpward, Green, st.upBps, "سرعة الرفع")
                SpeedStat(Modifier.weight(1f), Icons.Filled.ArrowDownward, Blue, st.downBps, "سرعة التنزيل")
            }
        }
        st.error?.let { Text(it, color = Red, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally)) }
        Spacer(Modifier.height(9.dp))
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().panel().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoCard(Modifier.weight(1f), Icons.Filled.Shield, "حماية الإنترنت",
                    if (st.connected) "نشط" else "متوقف", if (st.connected) Green else Dim) {}
                InfoCard(Modifier.weight(1f), Icons.AutoMirrored.Filled.Send, "البروتوكول",
                    st.current?.protocol ?: "—", Color.White, onProtocol)
            }
        }
        Spacer(Modifier.height(10.dp))
        AppExclusionSection(st)
        Spacer(Modifier.height(8.dp))
    }
    if (showDeveloper) {
        AlertDialog(
            onDismissRequest = { showDeveloper = false },
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Image(painterResource(R.drawable.developer_avatar), contentDescription = "محمد",
                        modifier = Modifier.size(112.dp).clip(CircleShape).border(3.dp, Red, CircleShape),
                        contentScale = ContentScale.Crop)
                    Spacer(Modifier.height(10.dp))
                    Text("تجاوز حظر شبكات العراق", color = Red, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("المطور محمد 🇮🇶", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("معرف التلكرام: @seloom1", color = Blue, fontSize = 13.sp)
                    Text("قناة التلكرام: @freevpsiraq", color = Blue, fontSize = 13.sp)
                }
            },
            confirmButton = {
                Button(onClick = { showDeveloper = false }, colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                    Text("إغلاق")
                }
            }
        )
    }
}

@Composable
private fun AppExclusionSection(st: UiState) {
    val ctx = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var appsLoading by remember { mutableStateOf(true) }
    var selectedApps by remember { mutableStateOf(st.excludedApps) }
    var appSearch by remember { mutableStateOf("") }
    LaunchedEffect(ctx) {
        installedApps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA).map { info ->
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
                val icon = runCatching { pm.getApplicationIcon(info) }.getOrElse { pm.defaultActivityIcon }
                InstalledApp(info.packageName, label, icon)
            }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        }
        appsLoading = false
    }
    val visibleApps = remember(installedApps, appSearch, selectedApps) {
        installedApps.filter { it.label.contains(appSearch, true) || it.packageName.contains(appSearch, true) }
            .sortedWith(compareByDescending<InstalledApp> { it.packageName in selectedApps }.thenBy { it.label.lowercase() })
    }
    Column(Modifier.fillMaxWidth().panel().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("استثناء التطبيقات", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("تستخدم اتصال الهاتف المباشر", color = Dim, fontSize = 10.sp)
            }
            Text("${st.excludedApps.size}", color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Spacer(Modifier.height(5.dp))
        OutlinedButton(onClick = {
            selectedApps = st.excludedApps
            appSearch = ""
            showPicker = !showPicker
        }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Apps, null)
            Spacer(Modifier.width(8.dp))
            Text(if (showPicker) "إخفاء التطبيقات" else "اختيار التطبيقات", fontSize = 12.sp)
        }
        if (showPicker) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedTextField(value = appSearch, onValueChange = { appSearch = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("بحث باسم التطبيق") })
                Spacer(Modifier.height(8.dp))
                when {
                    appsLoading -> CircularProgressIndicator(color = Red, modifier = Modifier.align(Alignment.CenterHorizontally))
                    visibleApps.isEmpty() -> Text("لم يتم العثور على تطبيقات.", color = Dim, modifier = Modifier.padding(12.dp))
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(visibleApps, key = { it.packageName }) { app ->
                            val isVpnApp = app.packageName == ctx.packageName
                            val icon = remember(app.packageName) { app.icon.toSmallImageBitmap() }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = app.packageName in selectedApps, enabled = !isVpnApp,
                                    onCheckedChange = { checked ->
                                        selectedApps = if (checked) selectedApps + app.packageName else selectedApps - app.packageName
                                    })
                                Image(icon, contentDescription = app.label,
                                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, color = TextPrimary, maxLines = 1)
                                    Text(if (isVpnApp) "مستثنى للحماية" else app.packageName,
                                        color = Dim, fontSize = 10.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    Vpn.saveExcludedApps(ctx, selectedApps)
                    showPicker = false
                    if (st.connected || st.connecting) Vpn.start(ctx)
                    Toast.makeText(ctx, "تم حفظ استثناءات التطبيقات", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                    Icon(Icons.Filled.Save, null)
                    Spacer(Modifier.width(8.dp))
                    Text("حفظ الاستثناءات")
                }
            }
        }
    }
}

@Composable
private fun ExitIpCard(st: UiState) {
    Row(Modifier.fillMaxWidth().panel().padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Public, null, tint = Red, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("عنوان IP عبر VPN", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text(when {
                st.publicIp != null -> st.publicIp
                st.ipLoading && (st.connected || st.connecting) -> "جارٍ تحديد IP…"
                st.connected -> "تعذر تحديد IP"
                else -> "اتصل بالـVPN لعرضه"
            }, color = Dim, fontSize = 10.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 8.dp)) {
            Text("الدولة", color = Dim, fontSize = 9.sp)
            Text(st.countryName ?: "—", color = TextPrimary, fontSize = 10.sp, maxLines = 1)
        }
        Box(Modifier.size(28.dp).clip(CircleShape).background(DeepRed.copy(0.6f)), contentAlignment = Alignment.Center) {
            countryFlagEmoji(st.countryCode)?.let { Text(it, fontSize = 16.sp, maxLines = 1) }
                ?: Icon(Icons.Filled.Public, null, tint = Red, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
fun Hero() = Row(Modifier.fillMaxWidth().height(72.dp).panel().padding(6.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
    BrandLogoTile(Modifier.weight(1f).fillMaxHeight(), R.drawable.asiacell_red_logo, "ASIACELL RED", Red)
    Column(Modifier.width(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(23.dp).background(DeepRed.copy(alpha = 0.65f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Link, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Text("VPN", color = Dim, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
    BrandLogoTile(Modifier.weight(1f).fillMaxHeight(), R.drawable.zain_logo_white, "ZAIN", Color(0xFFA78BFA))
}

@Composable
private fun BrandLogoTile(modifier: Modifier, imageRes: Int, label: String, accent: Color) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.clip(shape)
        .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.18f), Panel)))
        .border(1.dp, accent.copy(alpha = 0.48f), shape)
        .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(imageRes), contentDescription = label,
            modifier = Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
        Text(label, color = accent, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 0.6.sp, maxLines = 1)
    }
}

@Composable
fun PowerButton(st: UiState, onClick: () -> Unit) {
    val glow by rememberInfiniteTransition(label = "g").animateFloat(0.55f, 1f,
        infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "a")
    val on = st.connected
    val powerColor = if (on) Green else Red
    Box(Modifier.padding(horizontal = 1.dp).size(142.dp).drawBehind {
        drawCircle(Brush.radialGradient(listOf(powerColor.copy(if (on) 0.5f * glow else 0.18f), Color.Transparent)), size.minDimension * 0.62f)
    }.clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(132.dp).border(3.dp, Color(0xFF2A2326), CircleShape).padding(6.dp)
            .border(3.dp, powerColor, CircleShape).background(Color(0xFF090611), CircleShape), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (st.connecting) CircularProgressIndicator(color = powerColor, modifier = Modifier.size(42.dp), strokeWidth = 3.dp)
                else Icon(Icons.Filled.PowerSettingsNew, null, tint = powerColor, modifier = Modifier.size(48.dp))
                Text(if (on) "متصل" else if (st.connecting) "جارٍ الاتصال" else "متوقف", color = powerColor,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun HeartbeatWave(connected: Boolean, uploadBps: Long, downloadBps: Long) {
    val activity = (maxOf(uploadBps, downloadBps).coerceAtLeast(0L) / 160_000f).coerceIn(0f, 1f)
    val period = (1850 - (activity * 1150).toInt()).coerceIn(650, 1850)
    val transition = rememberInfiniteTransition(label = "heartbeat")
    val phase by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    Canvas(Modifier.fillMaxWidth().height(46.dp)) {
        val mid = size.height / 2f
        drawLine(DeepRed.copy(alpha = 0.7f), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
        val beat = floatArrayOf(0f, 0f, 0.05f, -0.08f, 0.10f, 0f, 0f, 0.70f, -0.92f, 0.22f, 0f, 0.04f, 0f)
        fun pulse(offset: Float, color: Color) {
            val path = Path()
            val count = 144
            for (i in 0..count) {
                val xRatio = i / count.toFloat()
                val position = ((xRatio * 2.2f + phase + offset) % 1f) * (beat.size - 1)
                val sample = position.toInt().coerceIn(0, beat.lastIndex - 1)
                val fraction = position - sample
                val wave = beat[sample] + (beat[sample + 1] - beat[sample]) * fraction
                val intensity = if (connected) 0.05f + activity * 0.45f else 0.015f
                val y = mid - wave * size.height * intensity
                if (i == 0) path.moveTo(0f, y) else path.lineTo(size.width * xRatio, y)
            }
            drawPath(path, color.copy(alpha = 0.18f), style = Stroke(width = 6f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawPath(path, color, style = Stroke(width = 2f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        pulse(0f, Green)
        pulse(0.43f, Blue.copy(alpha = 0.85f))
    }
}

@Composable
private fun SpeedStat(modifier: Modifier, icon: ImageVector, color: Color, bytesPerSecond: Long, label: String) {
    val (display, unit) = when {
        bytesPerSecond >= 1_048_576 -> "%.2f".format(bytesPerSecond / 1_048_576f) to "MB/s"
        bytesPerSecond >= 1024 -> "%.1f".format(bytesPerSecond / 1024f) to "KB/s"
        else -> bytesPerSecond.coerceAtLeast(0).toString() to "B/s"
    }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.07f))
        .padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(display, color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                Spacer(Modifier.width(3.dp))
                Text(unit, color = TextPrimary, fontSize = 9.sp, modifier = Modifier.padding(bottom = 2.dp))
            }
            Text(label, color = Dim, fontSize = 9.sp)
        }
    }
}

private fun countryFlagEmoji(countryCode: String?): String? {
    val code = countryCode?.uppercase()?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } } ?: return null
    val base = 0x1F1E6
    return String(Character.toChars(base + code[0].code - 'A'.code)) +
        String(Character.toChars(base + code[1].code - 'A'.code))
}

@Composable
fun Flag(countryCode: String?) {
    val flag = countryFlagEmoji(countryCode)
    Box(Modifier.size(34.dp).clip(CircleShape).background(DeepRed.copy(0.6f)), contentAlignment = Alignment.Center) {
        if (flag != null) Text(flag, fontSize = 20.sp, maxLines = 1)
        else Icon(Icons.Filled.Public, null, tint = Red, modifier = Modifier.size(20.dp))
    }
}

private fun Drawable.toSmallImageBitmap(): ImageBitmap {
    val width = intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(96) ?: 64
    val height = intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(96) ?: 64
    val copy = constantState?.newDrawable()?.mutate() ?: this
    copy.setBounds(0, 0, width, height)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    copy.draw(AndroidCanvas(bitmap))
    return bitmap.asImageBitmap()
}

@Composable
fun InfoCard(m: Modifier, icon: ImageVector, title: String, value: String, vc: Color, onClick: () -> Unit) =
    Row(m.clip(Cut).border(1.dp, DeepRed, Cut).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Red, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
            Text(value, color = vc, fontSize = 13.sp, maxLines = 1)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Dim)
    }

@Composable
fun Links(st: UiState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editingSubscription by remember { mutableStateOf<String?>(null) }
    var editedSubscriptionName by remember { mutableStateOf("") }
    var newSubscriptionName by remember { mutableStateOf("") }
    var newSubscriptionUrl by remember { mutableStateOf("") }
    var expandedSources by remember { mutableStateOf(emptySet<String>()) }
    var loadingUrl by remember { mutableStateOf<String?>(null) }

    fun clipboardText(): String {
        val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return manager.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
    }

    fun updateSubscription(url: String, name: String? = null) {
        if (loadingUrl != null) return
        if (url.isBlank()) {
            Toast.makeText(ctx, "انسخ رابط الاشتراك أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        loadingUrl = url
        scope.launch {
            try {
                val count = Vpn.updateSubscription(ctx, url, name)
                expandedSources = expandedSources + url
                newSubscriptionName = ""
                newSubscriptionUrl = ""
                Toast.makeText(ctx, "تم تحديث الاشتراك: $count سيرفر", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "تعذّر تحديث الاشتراك", Toast.LENGTH_LONG).show()
            } finally {
                loadingUrl = null
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("السيرفرات", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("اشتراكاتك والسيرفرات الجاهزة للتشغيل", color = Dim, fontSize = 11.sp)
            }
            Box(Modifier.size(46.dp).clip(CircleShape).background(Red).clickable { showAddDialog = true },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, "إضافة", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().panel().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, tint = Green, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("المحدد للتشغيل:", color = Dim, fontSize = 11.sp)
            Spacer(Modifier.width(5.dp))
            Text(st.current?.name ?: "لا يوجد سيرفر", color = TextPrimary, fontWeight = FontWeight.Bold,
                fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f),
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(5.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 5.dp)) {
            if (st.subscriptions.isEmpty() && st.manualServers.isEmpty()) {
                item {
                    Empty("لا توجد سيرفرات", "اضغط + ثم أضف اشتراكاً أو سيرفر VLESS من الحافظة.")
                }
            }
            items(st.subscriptions, key = { "subscription:$it" }) { source ->
                val expanded = source in expandedSources
                SubscriptionCard(source = source, displayName = st.subscriptionNames[source] ?: "اشتراك سيرفرات",
                    servers = st.subscriptionServers[source].orEmpty(),
                    expanded = expanded, loading = loadingUrl == source,
                    selectedRaw = st.current?.raw,
                    onToggle = { expandedSources = if (expanded) expandedSources - source else expandedSources + source },
                    onRefresh = { updateSubscription(source) },
                    onRename = {
                        editingSubscription = source
                        editedSubscriptionName = st.subscriptionNames[source] ?: "اشتراك سيرفرات"
                    },
                    onRemove = {
                        Vpn.removeSubscription(ctx, source)
                        expandedSources = expandedSources - source
                    },
                    onSelect = { server ->
                        val index = st.servers.indexOfFirst { it.raw == server.raw }
                        if (index >= 0) Vpn.select(ctx, index)
                    })
            }
            if (st.manualServers.isNotEmpty()) {
                item {
                    Text("سيرفرات من الحافظة", color = Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                }
                items(st.manualServers, key = { "manual:${it.raw}" }) { server ->
                    val index = st.servers.indexOfFirst { it.raw == server.raw }
                    ServerChoiceCard(server, server.raw == st.current?.raw,
                        onSelect = { if (index >= 0) Vpn.select(ctx, index) },
                        onEdit = { if (index >= 0) editingIndex = index },
                        onRemove = { if (index >= 0) Vpn.remove(ctx, index) })
                }
            }
        }
    }
    if (showAddDialog) {
        AlertDialog(onDismissRequest = { showAddDialog = false },
            title = { Text("اشتراك جديد", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("أضف اسماً يسهل تمييزه ورابط الاشتراك.", color = Dim, fontSize = 12.sp)
                    OutlinedTextField(value = newSubscriptionName, onValueChange = { newSubscriptionName = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("اسم الاشتراك") },
                        placeholder = { Text("مثال: سيرفرات البيت") })
                    OutlinedTextField(value = newSubscriptionUrl, onValueChange = { newSubscriptionUrl = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("رابط HTTPS") },
                        placeholder = { Text("https://...") })
                    TextButton(onClick = { newSubscriptionUrl = clipboardText() }, modifier = Modifier.align(Alignment.End)) {
                        Icon(Icons.Filled.ContentPaste, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp)); Text("لصق الرابط")
                    }
                    OutlinedButton(onClick = {
                        val count = Vpn.import(ctx, clipboardText())
                        showAddDialog = false
                        Toast.makeText(ctx, if (count > 0) "تمت إضافة $count سيرفر" else "لا يوجد رابط VLESS صالح في الحافظة",
                            Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth(), enabled = loadingUrl == null) {
                        Icon(Icons.Filled.Link, null); Spacer(Modifier.width(7.dp)); Text("إضافة سيرفر VLESS من الحافظة")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val url = newSubscriptionUrl.trim()
                    if (!url.startsWith("https://", ignoreCase = true)) {
                        Toast.makeText(ctx, "أدخل رابط اشتراك يبدأ بـ https://", Toast.LENGTH_SHORT).show()
                    } else {
                        showAddDialog = false
                        updateSubscription(url, newSubscriptionName)
                    }
                }, enabled = loadingUrl == null && newSubscriptionUrl.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("إضافة اشتراك") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("إلغاء") } })
    }
    editingSubscription?.let { url ->
        AlertDialog(onDismissRequest = { editingSubscription = null },
            title = { Text("تسمية الاشتراك", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(value = editedSubscriptionName, onValueChange = { editedSubscriptionName = it },
                    singleLine = true, label = { Text("اسم الاشتراك") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                Button(onClick = {
                    Vpn.renameSubscription(ctx, url, editedSubscriptionName)
                    editingSubscription = null
                }, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("حفظ الاسم") }
            },
            dismissButton = { TextButton(onClick = { editingSubscription = null }) { Text("إلغاء") } })
    }
    editingIndex?.let { index ->
        st.servers.getOrNull(index)?.let { server ->
            EditServerDialog(index, server, ctx, onDismiss = { editingIndex = null })
        }
    }
}

@Composable
private fun SubscriptionCard(
    source: String,
    displayName: String,
    servers: List<Server>,
    expanded: Boolean,
    loading: Boolean,
    selectedRaw: String?,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onSelect: (Server) -> Unit
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(Brush.horizontalGradient(listOf(Panel, Color(0xFF21102D), Panel)))
        .border(1.dp, Brush.horizontalGradient(listOf(DeepRed, Red.copy(alpha = 0.45f), DeepRed)), RoundedCornerShape(18.dp))
        .padding(11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onToggle)) {
                Text(displayName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(source, color = Dim, fontSize = 10.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Box(Modifier.clip(CircleShape).background(Green.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 5.dp)) {
                Text("${servers.size} سيرفر", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.size(37.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(17.dp), color = Blue, strokeWidth = 2.dp)
                else Icon(Icons.Filled.Refresh, "تحديث الاشتراك", tint = Blue, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onToggle, modifier = Modifier.size(34.dp)) {
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    if (expanded) "طي الاشتراك" else "عرض السيرفرات", tint = Red)
            }
        }
        if (expanded) {
            HorizontalDivider(color = DeepRed.copy(alpha = 0.75f), modifier = Modifier.padding(vertical = 7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.weight(1f)) {
                    if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = Green, strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp)); Text(if (loading) "جارٍ التحديث" else "تحديث الاشتراك", fontSize = 11.sp)
                }
                OutlinedButton(onClick = onRename, enabled = !loading) {
                    Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp)); Text("تسمية", fontSize = 11.sp)
                }
                OutlinedButton(onClick = onRemove, enabled = !loading) {
                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp)); Text("حذف", fontSize = 11.sp)
                }
            }
            if (servers.isEmpty()) {
                Text("لا توجد سيرفرات؛ حدّث الاشتراك لجلبها.", color = Dim, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp))
            } else {
                Spacer(Modifier.height(5.dp))
                servers.forEach { server ->
                    ServerChoiceCard(server, server.raw == selectedRaw, onSelect = { onSelect(server) })
                    Spacer(Modifier.height(5.dp))
                }
            }
        }
    }
}

@Composable
private fun ServerChoiceCard(
    server: Server,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
        .background(if (selected) Green.copy(alpha = 0.10f) else Color(0xFF100A16))
        .border(1.dp, if (selected) Green.copy(alpha = 0.8f) else DeepRed.copy(alpha = 0.7f), RoundedCornerShape(11.dp))
        .clickable(onClick = onSelect).padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(server.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("${server.protocol} · ${server.host}:${server.port}", color = Dim, fontSize = 9.sp,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (selected) Text("محدد للتشغيل", color = Green, fontSize = 9.sp)
        }
        if (selected) Icon(Icons.Filled.CheckCircle, "محدد", tint = Green, modifier = Modifier.size(18.dp))
        onEdit?.let { callback -> IconButton(onClick = callback, modifier = Modifier.size(31.dp)) {
            Icon(Icons.Filled.Edit, "تعديل", tint = Blue, modifier = Modifier.size(16.dp))
        } }
        onRemove?.let { callback -> IconButton(onClick = callback, modifier = Modifier.size(31.dp)) {
            Icon(Icons.Filled.Delete, "حذف", tint = Dim, modifier = Modifier.size(16.dp))
        } }
    }
}

private fun serverFormat(server: Server): String {
    val transport = server.outbound.optJSONObject("transport")?.optString("type")
    val tls = server.outbound.optJSONObject("tls")?.optBoolean("enabled", false) == true
    return listOf(server.protocol.lowercase(), transport, if (tls) "tls" else null)
        .filterNotNull().joinToString(" ")
}

@Composable
private fun EditServerDialog(index: Int, server: Server, ctx: Context, onDismiss: () -> Unit) {
    if (server.protocol == "VLESS") {
        VlessEditDialog(index, server, ctx, onDismiss)
        return
    }
    var name by remember(server) { mutableStateOf(server.name) }
    var host by remember(server) { mutableStateOf(server.host) }
    var port by remember(server) { mutableStateOf(server.port.toString()) }
    var format by remember(server) { mutableStateOf(serverFormat(server)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل السيرفر") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("اسم السيرفر") }, singleLine = true)
                OutlinedTextField(host, { host = it }, label = { Text("IP أو النطاق") }, singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("البورت") }, singleLine = true)
                OutlinedTextField(format, { format = it }, label = { Text("الصيغة: vless ws tls أو ws") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = {
                val ok = Vpn.editServer(ctx, index, name,
                    host.trim(), port.toIntOrNull() ?: -1, format)
                if (ok) { Toast.makeText(ctx, "تم تعديل السيرفر", Toast.LENGTH_SHORT).show(); onDismiss() }
                else Toast.makeText(ctx, "تحقق من البيانات المدخلة", Toast.LENGTH_SHORT).show()
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun VlessEditDialog(index: Int, server: Server, ctx: Context, onDismiss: () -> Unit) {
    val tls = server.outbound.optJSONObject("tls")
    val reality = tls?.optJSONObject("reality")
    val utls = tls?.optJSONObject("utls")
    val transport = server.outbound.optJSONObject("transport")
    val wsHeaders = transport?.optJSONObject("headers")
    var name by remember(server) { mutableStateOf(server.name) }
    var host by remember(server) { mutableStateOf(server.host) }
    var port by remember(server) { mutableStateOf(server.port.toString()) }
    var uuid by remember(server) { mutableStateOf(server.outbound.optString("uuid")) }
    var flow by remember(server) { mutableStateOf(server.outbound.optString("flow")) }
    var security by remember(server) { mutableStateOf(if (reality != null) "reality" else if (tls != null) "tls" else "none") }
    var sni by remember(server) { mutableStateOf(tls?.optString("server_name", "") ?: "") }
    var alpn by remember(server) { mutableStateOf(tls?.optJSONArray("alpn")?.let { a -> (0 until a.length()).joinToString(",") { a.optString(it) } } ?: "") }
    var insecure by remember(server) { mutableStateOf(tls?.optBoolean("insecure", false) == true) }
    var fingerprint by remember(server) { mutableStateOf(utls?.optString("fingerprint", "") ?: "") }
    var realityKey by remember(server) { mutableStateOf(reality?.optString("public_key", "") ?: "") }
    var realitySid by remember(server) { mutableStateOf(reality?.optString("short_id", "") ?: "") }
    var transportType by remember(server) { mutableStateOf(transport?.optString("type", "tcp") ?: "tcp") }
    var pathOrService by remember(server) { mutableStateOf(transport?.let { it.optString("path", it.optString("service_name", "/")) } ?: "/") }
    var transportHost by remember(server) { mutableStateOf(wsHeaders?.optString("Host", "") ?: transport?.optString("host", "") ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل كل إعدادات VLESS") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("البيانات الأساسية", color = Red, fontWeight = FontWeight.Bold)
                EditField("اسم السيرفر", name) { name = it }
                EditField("IP أو النطاق", host) { host = it }
                EditField("البورت", port) { port = it.filter(Char::isDigit) }
                EditField("UUID", uuid) { uuid = it }
                EditField("Flow (اختياري)", flow) { flow = it }
                Text("الأمان والنقل", color = Red, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
                EditField("الأمان: none أو tls أو reality", security) { security = it.lowercase() }
                EditField("SNI / Server Name", sni) { sni = it }
                EditField("ALPN مثل h2,http/1.1", alpn) { alpn = it }
                EditField("بصمة uTLS مثل chrome", fingerprint) { fingerprint = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = insecure, onCheckedChange = { insecure = it })
                    Text("السماح بالشهادة غير الموثوقة (allow insecure)", color = TextPrimary, fontSize = 12.sp)
                }
                EditField("Reality Public Key", realityKey) { realityKey = it }
                EditField("Reality Short ID", realitySid) { realitySid = it }
                Text("النقل", color = Red, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
                EditField("النقل: tcp أو ws أو grpc أو httpupgrade", transportType) { transportType = it.lowercase() }
                EditField("WS Path أو gRPC Service Name", pathOrService) { pathOrService = it }
                EditField("Host Header / HTTP Host", transportHost) { transportHost = it }
            }
        },
        confirmButton = {
            Button(onClick = {
                val ok = Vpn.editVless(ctx, index, name, host.trim(), port.toIntOrNull() ?: -1, uuid.trim(), flow.trim(),
                    security.trim(), sni.trim(), alpn.trim(), insecure, fingerprint.trim(), realityKey.trim(), realitySid.trim(),
                    transportType.trim(), pathOrService, transportHost.trim())
                if (ok) { Toast.makeText(ctx, "تم حفظ كل إعدادات VLESS", Toast.LENGTH_SHORT).show(); onDismiss() }
                else Toast.makeText(ctx, "تحقق من IP والبورت وUUID والإعدادات", Toast.LENGTH_SHORT).show()
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun EditField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
fun Empty(title: String, body: String) = Column(Modifier.fillMaxSize().padding(28.dp),
    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
    Text(body, color = Dim, modifier = Modifier.padding(top = 8.dp).alpha(0.9f))
}

@Composable
fun NavBar(sel: Int, onSel: (Int) -> Unit) {
    val items = listOf("الرئيسية" to Icons.Filled.Home, "استخدام البيانات" to Icons.Filled.DataUsage,
        "إعدادات" to Icons.Filled.Dashboard, "إدارة الروابط" to Icons.Filled.Link)
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).panel().height(64.dp)) {
        items.forEachIndexed { i, (l, ic) ->
            val on = sel == i
            Column(Modifier.weight(1f).fillMaxHeight().clickable { onSel(i) }, horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(ic, null, tint = if (on) Red else Dim, modifier = Modifier.size(24.dp))
                Text(l, color = if (on) Red else Dim, fontSize = 11.sp)
                if (on) Box(Modifier.padding(top = 3.dp).width(44.dp).height(2.dp).background(Red))
            }
        }
    }
}

@Composable
fun Footer() {
    val links = LocalUriHandler.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text("المطور", color = Dim, fontSize = 9.sp)
            Text("@seloom1", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                modifier = Modifier.clickable { runCatching { links.openUri("https://t.me/seloom1") } })
        }
        Column(Modifier.weight(1.2f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("قناة تيليگرام", color = Dim, fontSize = 9.sp)
            Row(Modifier.clickable { runCatching { links.openUri("https://t.me/freevpsiraq") } },
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.Send, null, tint = Blue, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(3.dp))
                Text("@freevpsiraq", color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text("الحقوق محفوظة", color = Dim, fontSize = 9.sp)
            Text("محمد 🇮🇶", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DataTotalCard(icon: ImageVector, color: Color, kb: Long, label: String) = Row(
    Modifier.fillMaxWidth().background(color.copy(alpha = 0.10f), RoundedCornerShape(12.dp)).padding(12.dp),
    verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = color, modifier = Modifier.size(32.dp))
    Spacer(Modifier.width(10.dp))
    Column {
        Text(UsageHistory.data(kb), color = color, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Text(label, color = Dim, fontSize = 11.sp)
    }
}

@Composable
fun SpeedScreen(st: UiState) {
    val ctx = LocalContext.current
    var sessions by remember { mutableStateOf(UsageHistory.read(ctx)) }
    LaunchedEffect(st.connected, st.totalUpKB, st.totalDownKB) {
        sessions = UsageHistory.read(ctx)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("استخدام البيانات", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text("الإجمالي الحالي وسجل كل جلسات الاتصال", color = Dim, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().panel().padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (st.connected) "النفق نشط" else "النفق غير نشط",
                    color = if (st.connected) Green else Dim, fontWeight = FontWeight.Bold)
                Text("منذ آخر اتصال", color = Dim, fontSize = 11.sp)
            }
            Spacer(Modifier.height(18.dp))
            DataTotalCard(Icons.Filled.ArrowUpward, Green, st.totalUpKB, "إجمالي الرفع")
            Spacer(Modifier.height(12.dp))
            DataTotalCard(Icons.Filled.ArrowDownward, Blue, st.totalDownKB, "إجمالي التنزيل")
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = DeepRed)
            Spacer(Modifier.height(12.dp))
            Text("زمن الاستجابة: ${if (st.pingMs >= 0) "${st.pingMs} ms" else "غير متاح"}", color = TextPrimary)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("سجل جلسات الاتصال", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("يحفظ وقت البدء والإيقاف وحجم البيانات لكل جلسة", color = Dim, fontSize = 11.sp)
            }
            Text("${sessions.size}/100", color = Red, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().panel().padding(12.dp)) {
            if (sessions.isEmpty()) {
                Text("لا توجد جلسات مكتملة بعد.", color = Dim, fontSize = 12.sp)
            } else {
                sessions.take(20).forEachIndexed { index, session ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(session.serverName, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                maxLines = 1, modifier = Modifier.weight(1f))
                            Text(session.protocol, color = Red, fontSize = 10.sp)
                        }
                        Text("بدء: ${UsageHistory.time(session.startedAt)}", color = Dim, fontSize = 10.sp)
                        Text("إيقاف: ${UsageHistory.time(session.endedAt)}", color = Dim, fontSize = 10.sp)
                        Text("رفع ${UsageHistory.data(session.uploadedKB)}  •  تنزيل ${UsageHistory.data(session.downloadedKB)}",
                            color = if (session.downloadedKB > 0) Blue else Green, fontSize = 11.sp)
                    }
                    if (index < sessions.take(20).lastIndex) HorizontalDivider(color = DeepRed.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { sessions = UsageHistory.read(ctx) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Refresh, null)
                    Spacer(Modifier.width(6.dp))
                    Text("تحديث")
                }
                OutlinedButton(onClick = { UsageHistory.clear(ctx); sessions = emptyList() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.DeleteSweep, null)
                    Spacer(Modifier.width(6.dp))
                    Text("حذف السجل")
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(st: UiState) {
    val ctx = LocalContext.current
    var diagnosticEntries by remember { mutableStateOf(DiagnosticLog.read(ctx)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("الإعدادات", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text("إدارة التطبيق والسيرفرات المحفوظة", color = Dim, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().panel().padding(18.dp)) {
            Text("الحالة الحالية", color = Dim, fontSize = 12.sp)
            Text(if (st.connected) "متصل" else "غير متصل", color = if (st.connected) Green else Color.White,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("السيرفرات المحفوظة: ${st.servers.size}", color = TextPrimary)
            Text("البروتوكولات: VLESS · VMess · Trojan · WireGuard", color = Dim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().panel().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("DNS الشبكة المحلية", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(if (st.localDnsEnabled) "مفعّل · أفضل للتوافق مع الشبكات" else "DNS مشفّر عبر النفق",
                        color = if (st.localDnsEnabled) Green else Dim, fontSize = 12.sp)
                }
                Switch(checked = st.localDnsEnabled,
                    onCheckedChange = { Vpn.setLocalDnsEnabled(ctx, it) })
            }
            Text("يبدأ التطبيق الآن بـ DNS المحلي لأن بعض الشبكات تحجب DNS المشفّر على المنفذ 853.",
                color = Dim, fontSize = 11.sp)
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().panel().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Bypass LAN route (VPN)", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(if (st.bypassLanRouteEnabled) "مفعّل" else "متوقف",
                        color = if (st.bypassLanRouteEnabled) Green else Dim, fontSize = 12.sp)
                }
                Switch(checked = st.bypassLanRouteEnabled,
                    onCheckedChange = { Vpn.setBypassLanRouteEnabled(ctx, it) })
            }
            Text("يسمح بالوصول إلى الراوتر والكاميرات وأجهزة الشبكة المحلية؛ عند تفعيله يستخدم مسارات IPv4 منفصلة دون مسار 0.0.0.0/0.",
                color = Dim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("سجل الاتصال والتشخيص", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("${diagnosticEntries.size}/100", color = Dim, fontSize = 11.sp)
        }
        Text("يسجل أحداث الاتصال وأسباب الفصل فقط، ولا يحفظ مفاتيح WireGuard أو إعدادات VLESS.",
            color = Dim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().panel().padding(12.dp)) {
            if (diagnosticEntries.isEmpty()) {
                Text("لا توجد أحداث مسجلة بعد.", color = Dim, fontSize = 12.sp)
            } else {
                diagnosticEntries.take(20).forEach { entry ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(DiagnosticLog.formatTime(entry.at), color = Blue, fontSize = 10.sp)
                        Text(entry.message, color = TextPrimary, fontSize = 12.sp)
                    }
                    if (entry != diagnosticEntries.take(20).last())
                        HorizontalDivider(color = DeepRed.copy(alpha = 0.55f))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { diagnosticEntries = DiagnosticLog.read(ctx) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Refresh, null)
                    Spacer(Modifier.width(6.dp))
                    Text("تحديث")
                }
                OutlinedButton(onClick = {
                    DiagnosticLog.clear(ctx)
                    diagnosticEntries = emptyList()
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.DeleteSweep, null)
                    Spacer(Modifier.width(6.dp))
                    Text("مسح السجل")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("لإضافة سيرفر: انسخ الرابط، ثم افتح «إدارة الروابط» واضغط «لصق من الحافظة». بعد اختيار السيرفر اضغط زر الاتصال من الرئيسية.",
            color = Dim, fontSize = 13.sp)
    }
}
