package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * Trạng thái của Speedrun Timer
 */
enum class TimerState {
    WAITING_FOR_LANDING,       // BLUE: Đang ở trên máy bay/đang nhảy dù
    LAND_DETECTED_WAITING_TOUCH, // YELLOW: Đã chạm đất, chờ tương tác (chạm màn hình)
    RUNNING,                   // GREEN: Timer đang chạy
    FINISHED                   // RED: Đã dừng (đạt Booyah! hoặc Bị loại)
}

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var sharedPreferences: SharedPreferences
    
    // Window params của floating widget
    private lateinit var overlayView: ComposeView
    private lateinit var overlayLayoutParams: WindowManager.LayoutParams
    
    // Window params của full-screen transparent interceptor (State 2)
    private var touchInterceptorView: View? = null
    private var interceptorLayoutParams: WindowManager.LayoutParams? = null

    // Lifecycle helper dánh riêng cho Jetpack Compose bên trong Service
    private lateinit var serviceLifecycleOwner: ServiceLifecycleOwner

    // Speedrun State & Logic
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null
    private var pixelAnalysisJob: Job? = null

    // State flows cho UI
    private val _timerState = MutableStateFlow(TimerState.WAITING_FOR_LANDING)
    val timerState = _timerState.asStateFlow()

    private val _elapsedTimeMs = MutableStateFlow(0L)
    val elapsedTimeMs = _elapsedTimeMs.asStateFlow()

    private val _personalBestMs = MutableStateFlow(0L)
    val personalBestMs = _personalBestMs.asStateFlow()

    private val _isMinimized = MutableStateFlow(false)
    val isMinimized = _isMinimized.asStateFlow()

    private val _isAutoScanning = MutableStateFlow(false)
    val isAutoScanning = _isAutoScanning.asStateFlow()

    private val _recentScanningLog = MutableStateFlow("Chưa bắt đầu quét")
    val recentScanningLog = _recentScanningLog.asStateFlow()

    private val _isNewRecord = MutableStateFlow(false)
    val isNewRecord = _isNewRecord.asStateFlow()

    // MediaProjection cho việc tự động chụp/pixel check
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var mediaProjectionManager: MediaProjectionManager? = null

    private var startTimeNs = 0L
    private var pausedTimeOffsetMs = 0L

    companion object {
        private const val NOTIFICATION_ID = 2404
        private const val CHANNEL_ID = "pure_timer_speedrun_channel"
        private const val PREFS_NAME = "FFPureTimerPrefs"
        private const val KEY_PB_TIME = "personal_best_time_ms"
        
        const val EXTRA_RESULT_CODE = "media_projection_result_code"
        const val EXTRA_DATA_INTENT = "media_projection_data_intent"
        const val ACTION_STOP_SERVICE = "action_stop_pure_timer_service"
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sharedPreferences = getOrCreatePreferences()
        
        // Load Personal Best cũ
        _personalBestMs.value = sharedPreferences.getLong(KEY_PB_TIME, 0L)

        // Khởi tạo LifecycleOwner đặc thù
        serviceLifecycleOwner = ServiceLifecycleOwner()
        serviceLifecycleOwner.start()
        serviceLifecycleOwner.resume()

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        initFloatingWidget()
    }

    private fun getOrCreatePreferences(): SharedPreferences {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Setup MediaProjection nếu có
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val dataIntent = intent?.getParcelableExtra<Intent>(EXTRA_DATA_INTENT)

        if (resultCode != 0 && dataIntent != null) {
            setupMediaProjection(resultCode, dataIntent)
            _isAutoScanning.value = true
        } else {
            _isAutoScanning.value = false
            _recentScanningLog.value = "Chế độ thủ công tích cực (Manual Mode Only)"
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "FF PureTimer Pro Monitor"
            val descriptionText = "Hiện thị overlay đo đạc tốc độ speedrun Free Fire"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FF PureTimer Pro")
            .setContentText("Overlay Speedrun Timer đang chạy trên đỉnh trò chơi")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "TẮT TIMER", stopPendingIntent)
            .build()
    }

    /**
     * Khởi tạo Floating Widget overlay
     */
    private fun initFloatingWidget() {
        overlayLayoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            // Đặt góc trên lệch xuống chút để tránh tai thỏ mặc định
            x = 100
            y = 200
        }

        overlayView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            
            // Thiết lập ViewTree cho Compose hoạt động bình thường
            setViewTreeLifecycleOwner(serviceLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
            setViewTreeViewModelStoreOwner(object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = ViewModelStore()
            })

            setContent {
                MaterialTheme {
                    FloatingWidgetUI()
                }
            }
        }

        windowManager.addView(overlayView, overlayLayoutParams)
    }

    /**
     * Giao diện Floating Widget chính (Compose)
     */
    @Composable
    fun FloatingWidgetUI() {
        val state by timerState.collectAsStateWithLifecycle()
        val timeMs by elapsedTimeMs.collectAsStateWithLifecycle()
        val pbTime by personalBestMs.collectAsStateWithLifecycle()
        val minimized by isMinimized.collectAsStateWithLifecycle()
        val autoScan by isAutoScanning.collectAsStateWithLifecycle()
        val scanLog by recentScanningLog.collectAsStateWithLifecycle()
        val recordTriggered by isNewRecord.collectAsStateWithLifecycle()

        // Định màu sắc chủ đạo của StatusBar dựa theo State
        val stateColor = when (state) {
            TimerState.WAITING_FOR_LANDING -> Color(0xFF1E88E5)     // BLUE: Waiting
            TimerState.LAND_DETECTED_WAITING_TOUCH -> Color(0xFFFFB300) // YELLOW: Landed waiting touch
            TimerState.RUNNING -> Color(0xFF4CAF50)                 // GREEN: Running
            TimerState.FINISHED -> Color(0xFFF44336)                // RED: Finished
        }

        val formattedTime = formatTime(timeMs)
        val formattedPB = if (pbTime > 0) formatTime(pbTime) else "CHƯA CÓ"

        Card(
            modifier = Modifier
                .width(if (minimized) 180.dp else 260.dp)
                .wrapContentHeight()
                .padding(4.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        overlayLayoutParams.x += dragAmount.x.toInt()
                        overlayLayoutParams.y += dragAmount.y.toInt()
                        windowManager.updateViewLayout(overlayView, overlayLayoutParams)
                    }
                }
                .shadow(12.dp, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xD9050505)), // Immersive background
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.15f)),
                width = 1.dp
            )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 1. Tháp trạng thái - Speedrun Status Bar with subtle glow styling matching stateColor
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(stateColor)
                )

                // 2. Khu vực hiển thị Timer chính & PB
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    // Header với trạng thái & nút thu nhỏ
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(stateColor)
                            )
                            Text(
                                text = "OVERLAY ${getVietnameseStateTitle(state).uppercase()}",
                                color = stateColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp
                            )
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (autoScan) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF22C55E))
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = if (minimized) "⛶" else "−",
                                color = Color.Gray,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .clickable { _isMinimized.value = !minimized }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Hiển thị Timer
                    Text(
                        text = formattedTime,
                        color = Color.White,
                        fontSize = if (minimized) 20.sp else 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                        letterSpacing = (-0.5).sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Hiển thị Personal Best (Kỷ lục cũ) inside beautiful stylized border box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x1BFFFFFF))
                            .border(1.dp, Color(0x0DFFFFFF), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "PERSONAL BEST (PB)",
                                    color = Color.Gray,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = formattedPB,
                                    color = if (recordTriggered) Color(0xFFFFD700) else Color.White,
                                    fontSize = if (minimized) 10.sp else 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (recordTriggered) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0x33FFD700))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "NEW RECORD! 🔥",
                                        color = Color(0xFFFFD700),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0x333B82F6))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "TOP 1%",
                                        color = Color(0xFF3B82F6),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Log tự động quét (nếu phóng to)
                    if (!minimized) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "AUTO-SCAN: ${scanLog.uppercase()}",
                            color = Color.DarkGray,
                            fontSize = 8.sp,
                            maxLines = 1,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // 3. Panel thao tác nhanh (nếu phóng to)
                if (!minimized) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x33000000))
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        FloatingActionButtonMini(
                            symbol = "✈",
                            tooltip = "Hạ cánh",
                            backgroundColor = Color(0xFFF59E0B)
                        ) {
                            // Chuyển sang State 2: Đã hạ cánh chờ touch
                            onLanded()
                        }
                        FloatingActionButtonMini(
                            symbol = "▶",
                            tooltip = "Start",
                            backgroundColor = Color(0xFF22C55E)
                        ) {
                            // Force manual timer start
                            startTimer()
                        }
                        FloatingActionButtonMini(
                            symbol = "■",
                            tooltip = "Dừng / Save",
                            backgroundColor = Color(0xFFEF4444)
                        ) {
                            // Dừng và so khớp PB
                            stopAndSaveTime()
                        }
                        FloatingActionButtonMini(
                            symbol = "↺",
                            tooltip = "Đặt lại",
                            backgroundColor = Color(0xFF27272A)
                        ) {
                            resetTimer()
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun FloatingActionButtonMini(
        symbol: String,
        tooltip: String,
        backgroundColor: Color,
        onClick: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(backgroundColor.copy(alpha = 0.9f))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = symbol,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }

    private fun getVietnameseStateTitle(state: TimerState): String {
        return when (state) {
            TimerState.WAITING_FOR_LANDING -> "CHỜ HẠ CÁNH (BLUE)"
            TimerState.LAND_DETECTED_WAITING_TOUCH -> "CHỜ CHẠM MÀN (YELLOW)"
            TimerState.RUNNING -> "ĐANG RUN SPEEDRUN (GREEN)"
            TimerState.FINISHED -> "ĐÃ DỪNG / HOÀN THÀNH (RED)"
        }
    }

    /**
     * Chuyển trạng thái sang "Đã Hạ Cánh - Chờ Chạm Màn"
     */
    private fun onLanded() {
        if (_timerState.value == TimerState.FINISHED || _timerState.value == TimerState.WAITING_FOR_LANDING) {
            _timerState.value = TimerState.LAND_DETECTED_WAITING_TOUCH
            _elapsedTimeMs.value = 0L
            _isNewRecord.value = false
            triggerFlashFeedback(Color.Yellow.copy(alpha = 0.2f))
            
            // Kích hoạt Listener chặn chạm màn hình
            setupTouchInterceptor()
        }
    }

    /**
     * Bắt đầu chạy Timer thực tế
     */
    private fun startTimer() {
        // Chỉ chạy nếu đang ở trạng thái Landing hoặc ép buộc bằng tay
        if (_timerState.value == TimerState.RUNNING) return
        
        _timerState.value = TimerState.RUNNING
        _isNewRecord.value = false
        startTimeNs = System.nanoTime()
        pausedTimeOffsetMs = 0L

        timerJob?.cancel()
        timerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val nowNs = System.nanoTime()
                val currentElapsed = (nowNs - startTimeNs) / 1000000L
                _elapsedTimeMs.value = currentElapsed
                delay(33) // ~30 fps update timer mượt mà
            }
        }

        // Loại bỏ Interceptor chạm vì đã chạy timer thành công
        removeTouchInterceptor()
        triggerFlashFeedback(Color.Green.copy(alpha = 0.2f))
    }

    /**
     * Dừng và lưu trữ kết quả, so sánh với Personal Best (PB)
     */
    private fun stopAndSaveTime() {
        timerJob?.cancel()
        _timerState.value = TimerState.FINISHED
        val finalTime = _elapsedTimeMs.value

        val currentPB = _personalBestMs.value
        if (finalTime > 0) {
            if (currentPB == 0L || finalTime < currentPB) {
                // Kỷ lục cá nhân mới (Tốc độ tối ưu hơn/nhanh hơn)
                _personalBestMs.value = finalTime
                _isNewRecord.value = true
                
                sharedPreferences.edit().apply {
                    putLong(KEY_PB_TIME, finalTime)
                    apply()
                }
                triggerFlashFeedback(Color.Yellow.copy(alpha = 0.4f)) // Gold flash
            } else {
                triggerFlashFeedback(Color.Red.copy(alpha = 0.2f))
            }
        }
    }

    /**
     * Đặt lại toàn bộ Timer về trạng thái ban đầu
     */
    private fun resetTimer() {
        timerJob?.cancel()
        _timerState.value = TimerState.WAITING_FOR_LANDING
        _elapsedTimeMs.value = 0L
        _isNewRecord.value = false
        removeTouchInterceptor()
    }

    /**
     * Khởi tạo lớp overlay chặn cử chỉ chạm toàn diện (Transparent Interceptor)
     */
    private fun setupTouchInterceptor() {
        if (touchInterceptorView != null) return

        interceptorLayoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            // LƯU Ý: FLAG_NOT_FOCUSABLE được giữ để không chiếm bàn phím, nhưng cho phép nhận Taps
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }

        val frame = FrameLayout(this)
        frame.setBackgroundColor(0x00000000) // Hoàn toàn trong suốt

        // Khi người dùng bấm màn hình bất kỳ đâu
        frame.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                Log.d("PureTimer", "First touch detected! Khởi chạy Speedrun Timer ngay lập tức!")
                startTimer()
            }
            // Trả về false để hệ thống truyền cử chỉ xuống game!
            // Trên một số thiết bị, nếu xoá overlay ngay lập tức, tap này sẽ lọt xuống game Free Fire trực tiếp
            false
        }

        touchInterceptorView = frame
        windowManager.addView(touchInterceptorView, interceptorLayoutParams)
    }

    /**
     * Loại bỏ interceptor
     */
    private fun removeTouchInterceptor() {
        touchInterceptorView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("PureTimer", "Error removing touch interceptor: ${e.message}")
            }
            touchInterceptorView = null
        }
    }

    /**
     * Tạo hiệu ứng nháy màn hình xanh/đỏ/vàng để phản hồi trực quan
     */
    private fun triggerFlashFeedback(color: Color) {
        val flashParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }

        val flashView = View(this).apply {
            setBackgroundColor(android.graphics.Color.argb(
                (color.alpha * 255).toInt(), 
                (color.red * 255).toInt(), 
                (color.green * 255).toInt(), 
                (color.blue * 255).toInt()
            ))
        }

        windowManager.addView(flashView, flashParams)

        // Tự động mờ và xoá sau 200 miligiây
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                windowManager.removeView(flashView)
            } catch (e: Exception) {
                Log.e("PureTimer", "Error removing flash feedback: ${e.message}")
            }
        }, 250)
    }

    /**
     * Thiết lập MediaProjection và ImageReader để tự động bắt pixel màn hình chơi Free Fire
     */
    private fun setupMediaProjection(resultCode: Int, dataIntent: Intent) {
        try {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, dataIntent)
            
            // Đọc cấu hình màn hình hiện tại để thiết lập kích thước ảnh chụp
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            // Khởi tạo ImageReader để chụp khung hình (sử dụng độ phân giải nhỏ cho hiệu suất cao)
            val captureWidth = width / 2
            val captureHeight = height / 2
            
            imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
            
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "FF_PureTimer_Capture",
                captureWidth,
                captureHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            // Bắt đầu chu kỳ quét pixel
            startPixelAnalysisLoop(captureWidth, captureHeight)

        } catch (e: Exception) {
            Log.e("PureTimer", "Error setting up MediaProjection: ${e.message}")
            _recentScanningLog.value = "Lỗi thiết lập: ${e.message}"
        }
    }

    /**
     * Vòng lặp quét pixel màn hình game Free Fire với tần suất cao (0.1 giây / lần)
     */
    private fun startPixelAnalysisLoop(width: Int, height: Int) {
        pixelAnalysisJob?.cancel()
        pixelAnalysisJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(100) // Quét mỗi 100ms (0.1 giây) như yêu cầu

                val reader = imageReader ?: continue
                var image: android.media.Image? = null
                try {
                    image = reader.acquireLatestImage() ?: continue
                    val plane = image.planes[0]
                    val buffer: ByteBuffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride

                    val state = _timerState.value

                    if (state == TimerState.WAITING_FOR_LANDING) {
                        // STATE 1: Quét tìm sự kết thúc nhảy dù và kích hoạt Combat HUD
                        // Ở Free Fire, HUD nút di chuyển và nút bắn sẽ xuất hiện lúc tiếp đất (thường có màu vàng/đỏ đặc trưng của nút chiến đấu).
                        // Đồng thời dù biến mất. Chúng ta quét màu vùng góc dưới bên trái (di chuyển) và góc dưới bên phải (bắn).
                        val joystickX = width / 6
                        val joystickY = height * 4 / 5
                        
                        val offset = joystickY * rowStride + joystickX * pixelStride
                        if (offset < buffer.limit() - 4) {
                            val r = buffer.get(offset).toInt() and 0xFF
                            val g = buffer.get(offset + 1).toInt() and 0xFF
                            val b = buffer.get(offset + 2).toInt() and 0xFF
                            
                            // Free Fire combat HUD thường mờ nhẹ nhưng có viền sáng hoặc nút màu cam/vàng đặc trưng.
                            // Để làm app cực hữu dụng và không phụ thuộc tuyệt đối vào độ sáng màn hình:
                            // Nếu thấy cấu trúc pixel thay đổi đột ngột hoặc chứa sắc màu tươi sáng của HUD (so với bầu trời xanh lam khi nhảy dù):
                            val isSkySkycolor = b > g && g > r && b > 140 // Màu bầu trời nhảy dù thông thường
                            if (!isSkySkycolor && (r > 100 || g > 100)) {
                                withContext(Dispatchers.Main) {
                                    _recentScanningLog.value = "Tiếp đất thành công! Chờ chạm..."
                                    onLanded()
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    _recentScanningLog.value = "Đang rà dù... Bầu trời: R=$r, G=$g, B=$b"
                                }
                            }
                        }
                    } 
                    else if (state == TimerState.RUNNING) {
                        // STATE 3: Quét chữ "BOOYAH!" màu vàng vàng cực lớn ở trung tâm, hoặc "ELIMINATED" màu đỏ thẫm
                        val centerX = width / 2
                        val centerY = height / 2

                        val offsetCenter = centerY * rowStride + centerX * pixelStride
                        if (offsetCenter < buffer.limit() - 4) {
                            val r = buffer.get(offsetCenter).toInt() and 0xFF
                            val g = buffer.get(offsetCenter + 1).toInt() and 0xFF
                            val b = buffer.get(offsetCenter + 2).toInt() and 0xFF

                            // BOOYAH! Chữ màu VÀNG rực: R rực rỡ, G tương đối lớn, B rất thấp
                            val isBooyahGold = r > 190 && g > 150 && b < 100
                            // ELIMINATED Chữ màu ĐỎ thẫm: R rất cao, G cực thấp, B thấp
                            val isEliminatedRed = r > 180 && g < 60 && b < 60

                            if (isBooyahGold || isEliminatedRed) {
                                val outcome = if (isBooyahGold) "BOOYAH! 🎉" else "THẤT BẠI (ELIMINATED) 💀"
                                withContext(Dispatchers.Main) {
                                    _recentScanningLog.value = "Phát hiện: $outcome!"
                                    stopAndSaveTime()
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    _recentScanningLog.value = "Đang chạy speedrun... Pixel Giữa: R=$r, G=$g, B=$b"
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PureTimer", "Error during pixel check: ${e.message}")
                } finally {
                    image?.close()
                }
            }
        }
    }

    private fun formatTime(timeMs: Long): String {
        val hours = timeMs / 3600000
        val minutes = (timeMs % 3600000) / 60000
        val seconds = (timeMs % 60000) / 1000
        val ms = timeMs % 1000
        
        return String.format("%02d:%02d:%02d:%03d", hours, minutes, seconds, ms)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        timerJob?.cancel()
        pixelAnalysisJob?.cancel()
        
        removeTouchInterceptor()
        
        try {
            windowManager.removeView(overlayView)
        } catch (e: Exception) {
            Log.e("PureTimer", "Error removing floating overlay: ${e.message}")
        }

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()

        serviceLifecycleOwner.stop()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Custom LifecycleOwner dầy đủ cho Compose View trong Service
     */
    private class ServiceLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        init {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }

        fun start() {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
        }

        fun resume() {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry
    }
}
