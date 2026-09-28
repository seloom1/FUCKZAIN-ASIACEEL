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
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF030304); private val Panel = Color(0xFF0D0507)
private val Red = Color(0xFFFF1F2E); private val DeepRed = Color(0xFF6B0A14)
private val Green = Color(0xFF19E68C); private val Blue = Color(0xFF4D7CFF)
private val Dim = Color(0xFFB4ADB0)
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
                MaterialTheme(colorScheme = darkColorScheme(primary = Red, background = Ink, surface = Panel)) { App() }
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
    drawRect(Brush.radialGradient(listOf(Color(0x33FF1F2E), Color.Transparent), Offset(w / 2, h * 0.36f), w * 0.9f))
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(Icons.Filled.Menu, null, tint = Color.White, modifier = Modifier.size(28.dp))
            Icon(Icons.Filled.Settings, null, tint = Color.White,
                modifier = Modifier.size(28.dp).clickable(onClick = onSettings))
        }
        Hero()
        Text("FUCK ASIACEEL & ZAIN", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black,
            letterSpacing = 1.sp, modifier = Modifier.align(Alignment.CenterHorizontally), maxLines = 1)
        Text("تباً لسياسة الاستخدام العادل", color = Red, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
        ExitIpCard(st)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).panel().padding(12.dp)) {
                Speed(Icons.Filled.ArrowUpward, Green, st.upKB, "رفع")
                Spacer(Modifier.height(12.dp))
                Speed(Icons.Filled.ArrowDownward, Blue, st.downKB, "تنزيل")
            }
            PowerButton(st, onToggle)
            Column(Modifier.weight(1f).panel().padding(12.dp), horizontalAlignment = Alignment.End) {
                val name = st.current?.name ?: "—"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("السيرفر الحالي", color = Dim, fontSize = 10.sp)
                        Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp)); Flag(st.countryCode)
                }
                Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(DeepRed))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("التأخير", color = Dim, fontSize = 10.sp)
                        Text(if (st.pingMs >= 0) "${st.pingMs} ms" else "— ms", color = Color.White, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(30.dp).background(DeepRed.copy(0.7f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.CellTower, null, tint = Red, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        st.error?.let { Text(it, color = Red, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally)) }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().panel().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoCard(Modifier.weight(1f), Icons.Filled.Shield, "حماية الإنترنت",
                    if (st.connected) "نشط" else "متوقف", if (st.connected) Green else Dim) {}
                InfoCard(Modifier.weight(1f), Icons.AutoMirrored.Filled.Send, "البروتوكول",
                    st.current?.protocol ?: "—", Color.White, onProtocol)
            }
        }
    }
}

@Composable
private fun ExitIpCard(st: UiState) {
    Row(Modifier.fillMaxWidth().panel().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Public, null, tint = Red, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("عنوان IP عبر VPN", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(when {
                st.publicIp != null -> st.publicIp
                st.ipLoading && (st.connected || st.connecting) -> "جارٍ تحديد IP…"
                st.connected -> "تعذر تحديد IP"
                else -> "اتصل بالـVPN لعرضه"
            }, color = Dim, fontSize = 12.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 8.dp)) {
            Text("الدولة", color = Dim, fontSize = 10.sp)
            Text(st.countryName ?: "—", color = Color.White, fontSize = 12.sp, maxLines = 1)
        }
        Flag(st.countryCode)
    }
}

@Composable
fun Hero() = Row(Modifier.fillMaxWidth().height(102.dp).panel().padding(8.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
    BrandLogoTile(Modifier.weight(1f).fillMaxHeight(), R.drawable.asiacell_red_logo, "ASIACELL RED", Red)
    Column(Modifier.width(38.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(27.dp).background(DeepRed.copy(alpha = 0.65f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Link, null, tint = Color.White, modifier = Modifier.size(17.dp))
        }
        Text("VPN", color = Dim, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
    Box(Modifier.padding(horizontal = 4.dp).size(132.dp).drawBehind {
        drawCircle(Brush.radialGradient(listOf(powerColor.copy(if (on) 0.5f * glow else 0.18f), Color.Transparent)), size.minDimension * 0.62f)
    }.clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(126.dp).border(3.dp, Color(0xFF2A2326), CircleShape).padding(6.dp)
            .border(3.dp, powerColor, CircleShape).background(Color.Black, CircleShape), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (st.connecting) CircularProgressIndicator(color = powerColor, modifier = Modifier.size(38.dp), strokeWidth = 3.dp)
                else Icon(Icons.Filled.PowerSettingsNew, null, tint = powerColor, modifier = Modifier.size(42.dp))
                Text(if (on) "متصل" else if (st.connecting) "جارٍ الاتصال" else "متوقف", color = powerColor,
                    fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
    }
}

@Composable
fun Speed(icon: ImageVector, c: Color, kb: Long, label: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(icon, null, tint = c, modifier = Modifier.size(30.dp))
    Spacer(Modifier.width(6.dp))
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            val display = if (kb >= 1000) "%.1f".format(kb / 1000f) else kb.toString()
            val unit = if (kb >= 1000) " MB/s" else " KB/s"
            Text(display, color = c, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(unit, color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        Text(label, color = Dim, fontSize = 11.sp)
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
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
            Text(value, color = vc, fontSize = 13.sp, maxLines = 1)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Dim)
    }

@Composable
fun Links(st: UiState) {
    val ctx = LocalContext.current
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("إدارة الروابط", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("VLESS · VMess · Trojan · WireGuard", color = Dim, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        Button(onClick = {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val txt = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            val n = Vpn.import(ctx, txt)
            Toast.makeText(ctx, if (n > 0) "تمت إضافة $n" else "لا يوجد رابط صالح في الحافظة", Toast.LENGTH_SHORT).show()
        }, colors = ButtonDefaults.buttonColors(containerColor = Red), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text("لصق من الحافظة")
        }
        Spacer(Modifier.height(14.dp))
        if (st.servers.isEmpty()) Text("انسخ رابط سيرفر ثم اضغط «لصق من الحافظة».", color = Dim)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(st.servers) { i, s ->
                Row(Modifier.fillMaxWidth().border(1.dp, if (i == st.selected) Red else DeepRed, RoundedCornerShape(14.dp))
                    .clickable { Vpn.select(ctx, i) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("${s.host}:${s.port}", color = Dim, fontSize = 12.sp, maxLines = 1)
                        Text(serverFormat(s), color = Red.copy(alpha = 0.9f), fontSize = 11.sp, maxLines = 1)
                    }
                    IconButton(onClick = { editingIndex = i }) { Icon(Icons.Filled.Edit, "تعديل", tint = Blue) }
                    IconButton(onClick = { Vpn.remove(ctx, i) }) { Icon(Icons.Filled.Delete, null, tint = Dim) }
                }
            }
        }
    }
    editingIndex?.let { index ->
        st.servers.getOrNull(index)?.let { server ->
            EditServerDialog(index, server, ctx, onDismiss = { editingIndex = null })
        }
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
fun Empty(title: String, body: String) = Column(Modifier.fillMaxSize().padding(28.dp),
    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Text(body, color = Dim, modifier = Modifier.padding(top = 8.dp).alpha(0.9f))
}

@Composable
fun NavBar(sel: Int, onSel: (Int) -> Unit) {
    val items = listOf("الرئيسية" to Icons.Filled.Home, "فحص السرعة" to Icons.Filled.Speed,
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
            Text("@seloom1", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                modifier = Modifier.clickable { runCatching { links.openUri("https://t.me/seloom1") } })
        }
        Column(Modifier.weight(1.2f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("قناة تيليگرام", color = Dim, fontSize = 9.sp)
            Row(Modifier.clickable { runCatching { links.openUri("https://t.me/freevpsiraq") } },
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.Send, null, tint = Blue, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(3.dp))
                Text("@freevpsiraq", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text("الحقوق محفوظة", color = Dim, fontSize = 9.sp)
            Text("محمد 🇮🇶", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

@Composable
fun SpeedScreen(st: UiState) = Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
    Text("فحص السرعة", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Text("قراءة الحركة من جهاز Android أثناء تشغيل النفق", color = Dim, fontSize = 13.sp)
    Spacer(Modifier.height(18.dp))
    Column(Modifier.fillMaxWidth().panel().padding(18.dp)) {
        Text(if (st.connected) "الاتصال نشط" else "الاتصال غير نشط",
            color = if (st.connected) Green else Dim, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Speed(Icons.Filled.ArrowUpward, Green, st.upKB, "رفع حالي")
        Spacer(Modifier.height(12.dp))
        Speed(Icons.Filled.ArrowDownward, Blue, st.downKB, "تنزيل حالي")
        Spacer(Modifier.height(12.dp))
        Text("زمن الاستجابة: ${if (st.pingMs >= 0) "${st.pingMs} ms" else "غير متاح"}", color = Color.White)
    }
}

@Composable
fun SettingsScreen(st: UiState) {
    val ctx = LocalContext.current
    var showAppPicker by remember { mutableStateOf(false) }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("الإعدادات", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("إدارة التطبيق والسيرفرات المحفوظة", color = Dim, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().panel().padding(18.dp)) {
            Text("الحالة الحالية", color = Dim, fontSize = 12.sp)
            Text(if (st.connected) "متصل" else "غير متصل", color = if (st.connected) Green else Color.White,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("السيرفرات المحفوظة: ${st.servers.size}", color = Color.White)
            Text("البروتوكولات: VLESS · VMess · Trojan · WireGuard", color = Dim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        Text("استثناء التطبيقات من VPN", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("التطبيقات المختارة ستستخدم اتصال الهاتف المباشر.", color = Dim, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            selectedApps = st.excludedApps
            appSearch = ""
            showAppPicker = !showAppPicker
        }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Apps, null)
            Spacer(Modifier.width(8.dp))
            Text(if (showAppPicker) "إخفاء قائمة التطبيقات" else "اختيار التطبيقات (${st.excludedApps.size})")
        }
        if (showAppPicker) {
            Column(Modifier.fillMaxWidth().panel().padding(12.dp)) {
                OutlinedTextField(value = appSearch, onValueChange = { appSearch = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("بحث باسم التطبيق") })
                Spacer(Modifier.height(8.dp))
                when {
                    appsLoading -> CircularProgressIndicator(color = Red, modifier = Modifier.align(Alignment.CenterHorizontally))
                    visibleApps.isEmpty() -> Text("لم يتم العثور على تطبيقات.", color = Dim, modifier = Modifier.padding(12.dp))
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(visibleApps, key = { it.packageName }) { app ->
                            val isVpnApp = app.packageName == ctx.packageName
                            val icon = remember(app.packageName) { app.icon.toSmallImageBitmap() }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = app.packageName in selectedApps, enabled = !isVpnApp,
                                    onCheckedChange = { checked ->
                                        selectedApps = if (checked) selectedApps + app.packageName else selectedApps - app.packageName
                                    })
                                Image(icon, contentDescription = app.label,
                                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, color = Color.White, maxLines = 1)
                                    Text(if (isVpnApp) "مستثنى للحماية؛ فحص IP عبر وكيل النفق" else app.packageName,
                                        color = Dim, fontSize = 10.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    Vpn.saveExcludedApps(ctx, selectedApps)
                    showAppPicker = false
                    if (st.connected || st.connecting) Vpn.start(ctx)
                    Toast.makeText(ctx, "تم حفظ إعدادات استثناء التطبيقات", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Red)) {
                    Icon(Icons.Filled.Save, null)
                    Spacer(Modifier.width(8.dp))
                    Text("حفظ الإعدادات")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("لإضافة سيرفر: انسخ الرابط، ثم افتح «إدارة الروابط» واضغط «لصق من الحافظة». بعد اختيار السيرفر اضغط زر الاتصال من الرئيسية.",
            color = Dim, fontSize = 13.sp)
    }
}
