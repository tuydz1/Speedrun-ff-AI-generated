package com.example

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private lateinit var sharedPreferences: SharedPreferences
    
    // Đăng ký nhận kết quả cấp quyền Notification cho Android 13+
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Đã cấp quyền thông báo!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Từ chối thông báo sẽ làm giảm độ bền của service nền!", Toast.LENGTH_LONG).show()
        }
    }

    // Đăng ký nhận kết quả chấp thuận quay màn hình (MediaProjection)
    private val startMediaProjectionRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startOverlayService(true, result.resultCode, result.data)
        } else {
            Toast.makeText(this, "Cần đồng ý Screen Capture để sử dụng chức năng tự động rà dù/Booyah!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        sharedPreferences = getSharedPreferences("FFPureTimerPrefs", Context.MODE_PRIVATE)

        // Kiểm tra permission cho thông báo
        checkNotificationPermission()

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F0F13)) // Deep cyber dark
                ) { innerPadding ->
                    DashboardScreen(
                        modifier = Modifier.padding(innerPadding),
                        onStartOverlayManual = {
                            if (checkOverlayPermission()) {
                                startOverlayService(false)
                            } else {
                                requestOverlayPermission()
                            }
                        },
                        onStartOverlayAuto = {
                            if (checkOverlayPermission()) {
                                triggerAutoSensingCapture()
                            } else {
                                requestOverlayPermission()
                            }
                        },
                        onStopOverlay = {
                            stopOverlayService()
                        }
                    )
                }
            }
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Toast.makeText(this, "Hãy tìm ứng dụng FF PureTimer Pro và cấp quyền 'Cửa sổ nổi' (Overlay)!", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = android.Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(permission)
            }
        }
    }

    private fun triggerAutoSensingCapture() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startMediaProjectionRequest.launch(intent)
    }

    private fun startOverlayService(autoScanning: Boolean, resultCode: Int = 0, dataIntent: Intent? = null) {
        val intent = Intent(this, OverlayService::class.java).apply {
            if (autoScanning && dataIntent != null) {
                putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                putExtra(OverlayService.EXTRA_DATA_INTENT, dataIntent)
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        Toast.makeText(this, "Đã khởi chạy speedrun overlay!", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        stopService(intent)
        Toast.makeText(this, "Đã ngắt toàn bộ overlay", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    onStartOverlayManual: () -> Unit,
    onStartOverlayAuto: () -> Unit,
    onStopOverlay: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val sharedPrefs = remember { context.getSharedPreferences("FFPureTimerPrefs", Context.MODE_PRIVATE) }
    
    // Theo dõi kỷ lục cá nhân
    var currentPB by remember { mutableStateOf(sharedPrefs.getLong("personal_best_time_ms", 0L)) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF050505)) // Immersive ultra-black
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Top mock status line for Immersive styling look
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF22C55E))
                )
                Text(
                    text = "ACTIVE SPEEDRUN CONSOLE",
                    color = Color(0xFF22C55E),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
            Text(
                text = "v1.2 PRO",
                color = Color.Gray,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // --- 1. Cyber Esports Header ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF18181B), Color(0xFF09090B))
                    )
                )
                .padding(20.dp)
        ) {
            Column {
                Text(
                    text = "FF PURETIMER PRO",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Thiết kế tối giản đo tốc độ ưu việt dành riêng cho tuyển thủ Free Fire Speedrun. Hoạt động như một widget nổi tự động rà dù và lưu kỷ lục.",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 2. Thống kê kỷ lục cá nhân (Personal Best Card) ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0x1AFFFDF0), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0x990A0A0F)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🏆 KỶ LỤC CÁ NHÂN (PB)",
                        color = Color(0xFFFFD700),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x26FFD700))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "TOP 1%",
                                color = Color(0xFFFFD700),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (currentPB > 0) {
                            Text(
                                text = "Reset",
                                color = Color(0xFFEF4444),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable {
                                        sharedPrefs.edit().remove("personal_best_time_ms").apply()
                                        currentPB = 0L
                                        Toast.makeText(context, "Đã xoá kỷ lục!", Toast.LENGTH_SHORT).show()
                                    }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                
                val formattedPB = if (currentPB > 0) {
                    val hours = currentPB / 3600000
                    val minutes = (currentPB % 3600000) / 60000
                    val seconds = (currentPB % 60000) / 1000
                    val ms = currentPB % 1000
                    String.format("%02d:%02d:%02d:%03d", hours, minutes, seconds, ms)
                } else {
                    "00:00:00:000"
                }

                Text(
                    text = formattedPB,
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- 3. Panel Quản Lý Thiết Lập ---
        Text(
            text = "⚡ ĐIỀU KHIỂN HOẠT ĐỘNG",
            color = Color.Gray,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Cột Trái: Chạy thủ công
            LaunchCard(
                title = "Thủ Công",
                desc = "Đo đạc thuần khiết lý tưởng, kiểm soát thủ công các cột mốc hoàn toàn bằng menu overlay.",
                buttonText = "Chạy thủ công",
                actionColor = Color(0xFF3B82F6),
                icon = Icons.Default.PlayArrow,
                onClick = onStartOverlayManual,
                modifier = Modifier.weight(1f)
            )

            // Cột Phải: Chạy thông minh
            LaunchCard(
                title = "Quét Tự Động",
                desc = "Quét độ sáng/sắp đặt màn hình phát hiện chạm đất và BOOYAH vàng rực để tự kích timer.",
                buttonText = "Chạy Auto-Scan",
                actionColor = Color(0xFF22C55E),
                icon = Icons.Default.Star,
                onClick = onStartOverlayAuto,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Nút tắt máy đo
        Button(
            onClick = onStopOverlay,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .border(1.dp, Color(0x33EF4444), RoundedCornerShape(12.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F0D0D)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Tắt", tint = Color(0xFFEF4444))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "TẮT TOÀN BỘ OVERLAY WIDGET", fontWeight = FontWeight.Bold, color = Color(0xFFEF4444))
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- 4. Hướng dẫn sử dụng chi tiết (Speedrun Guide) ---
        Text(
            text = "📖 QUY TRÌNH TỰ ĐỘNG CHUẨN",
            color = Color.Gray,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101014)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                StepIndicator(
                    stepNum = "1",
                    title = "Nhảy dù & Quét màn (BLUE)",
                    desc = "Bắt đầu game với widget trạng thái màu Xanh dương. Trực quan quét liên tục các tổ hợp pixel dù.",
                    accentColor = Color(0xFF3B82F6)
                )
                
                Divider(color = Color(0x1AFFFFFF), thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                StepIndicator(
                    stepNum = "2",
                    title = "Chạm Đất Sẵn Sàng (YELLOW & GREEN)",
                    desc = "Khi dạt tiếp đất, widget biến vàng rực rồi khóa màng chặn chạm. Từng cái chạm tay lướt súng đầu tiên sẽ bẻ khóa Timer tự động chạy tốc độ cao tức thì mà không có độ trễ.",
                    accentColor = Color(0xFFF59E0B)
                )

                Divider(color = Color(0x1AFFFFFF), thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                StepIndicator(
                    stepNum = "3",
                    title = "Booyah & Dừng tự động (RED)",
                    desc = "Khoảnh khắc chữ Booyah chói lọi xuất hiện, bộ phân tích rinh kết quả tắt timer, so đo PB lưu kỷ lục ngay.",
                    accentColor = Color(0xFFEF4444)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun LaunchCard(
    title: String,
    desc: String,
    buttonText: String,
    actionColor: Color,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(190.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12121A)),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.verticalGradient(listOf(Color.DarkGray, Color.Transparent))
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = actionColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = desc,
                    color = Color.Gray,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    maxLines = 5
                )
            }
            
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = actionColor),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(text = buttonText, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@Composable
fun StepIndicator(
    stepNum: String,
    title: String,
    desc: String,
    accentColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(accentColor)
                .align(Alignment.Top),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stepNum,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = desc,
                color = Color.Gray,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}
