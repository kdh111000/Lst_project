package com.gachon.lst;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCaptureService";
    private static final String CHANNEL_ID = "ScreenCaptureChannel";
    private static final long CAPTURE_INTERVAL_MS = 500L;   // 1초에 2장
    private static final long SUBTITLE_EXPIRY_MS = 1500L;  // 캡처 간격(500ms)의 3배 → 깜빡임 방지
    private static final int SUBTITLE_HEIGHT_PX = 50;       // 자막 높이 추정값 (충분히 여유있게)
    private static final int MIN_MATCH_DISTANCE_PX = 32;

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    private TextRecognizer textRecognizer;
    private Translator translator;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private long lastAnalyzedTimestamp = 0L;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private String targetLanguage = TranslateLanguage.ENGLISH;

    private WindowManager windowManager;
    private FrameLayout overlayContainer;
    private Handler mainHandler;
    private boolean isProjectionCallbackRegistered = false;
    private final AtomicLong subtitleIdGenerator = new AtomicLong(0L);

    private final ConcurrentHashMap<String, SubtitleItem> activeSubtitles = new ConcurrentHashMap<>();
    private final MediaProjection.Callback mediaProjectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.i(TAG, "MediaProjection stopped by system or user.");
            teardownCaptureSession();
            stopSelf();
        }
    };

    private static class SubtitleItem {
        String normalizedText;
        Rect sourceRect;
        TextView view;
        long lastSeenTime;
        Rect overlayRect; // 자막이 화면에서 차지하는 추정 영역 (자막 증식·겹침 방지용)
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        textRecognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());

        backgroundThread = new HandlerThread("ScreenCaptureThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("실시간 화면 번역기")
                .setContentText("원본 텍스트 바로 아래에 콤팩트한 자막을 표시합니다.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, notification);
        }

        if (intent.hasExtra("targetLang")) {
            targetLanguage = intent.getStringExtra("targetLang");
        }
        initTranslator();

        if (mediaProjection == null) {
            int resultCode = intent.getIntExtra("code", 0);
            Intent resultData = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ?
                    intent.getParcelableExtra("data", Intent.class) : intent.getParcelableExtra("data");

            if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
                projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
                mediaProjection.registerCallback(mediaProjectionCallback, backgroundHandler);
                isProjectionCallbackRegistered = true;

                setupOverlayContainer();
                startScreenCapture();
            }
        } else {
            mainHandler.post(() -> {
                if (overlayContainer != null) overlayContainer.removeAllViews();
            });
            activeSubtitles.clear();
        }

        return START_NOT_STICKY;
    }

    private void initTranslator() {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.KOREAN)
                .setTargetLanguage(targetLanguage)
                .build();

        if (translator != null) translator.close();
        translator = Translation.getClient(options);

        // [Fix 4] WiFi 조건 제거 → 모바일 데이터에서도 모델 다운로드 가능
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(v -> Log.d(TAG, "✅ 번역 모델 준비 완료: " + targetLanguage))
                .addOnFailureListener(e -> Log.w(TAG, "⚠️ 번역 모델 다운로드 실패: " + e.getMessage()));
    }

    private void setupOverlayContainer() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayContainer = new FrameLayout(this);

        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;

        mainHandler.post(() -> {
            if (windowManager != null && overlayContainer != null) {
                windowManager.addView(overlayContainer, params);
            }
        });
    }

    private void startScreenCapture() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, backgroundHandler);

        imageReader.setOnImageAvailableListener(reader -> {
            long currentTime = System.currentTimeMillis();

            // [Perf] 인터벌 미달이거나 이미 처리 중이면 프레임 버리기
            if (currentTime - lastAnalyzedTimestamp < CAPTURE_INTERVAL_MS
                    || !isProcessing.compareAndSet(false, true)) {
                Image skip = reader.acquireLatestImage();
                if (skip != null) skip.close();
                return;
            }
            lastAnalyzedTimestamp = currentTime;

            Image image = reader.acquireLatestImage();
            if (image == null) {
                isProcessing.set(false);
                return;
            }
            processImage(image, width, height);
        }, backgroundHandler);
    }

    private void teardownCaptureSession() {
        isProcessing.set(false);
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (mediaProjection != null) {
            if (isProjectionCallbackRegistered) {
                mediaProjection.unregisterCallback(mediaProjectionCallback);
                isProjectionCallbackRegistered = false;
            }
            mediaProjection.stop();
            mediaProjection = null;
        }

        List<TextView> viewsToRemove = new ArrayList<>();
        for (SubtitleItem item : activeSubtitles.values()) {
            if (item.view != null) {
                viewsToRemove.add(item.view);
                item.view = null;
            }
        }
        activeSubtitles.clear();
        mainHandler.post(() -> {
            if (overlayContainer != null) {
                for (TextView view : viewsToRemove) {
                    overlayContainer.removeView(view);
                }
                overlayContainer.removeAllViews();
            }
        });
    }

    private void processImage(Image image, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;

        Bitmap rawBitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        rawBitmap.copyPixelsFromBuffer(buffer);
        image.close();

        // [Perf] rowPadding 없으면 추가 복사 생략
        final Bitmap bitmap;
        if (rowPadding == 0) {
            bitmap = rawBitmap;
        } else {
            bitmap = Bitmap.createBitmap(rawBitmap, 0, 0, width, height);
            rawBitmap.recycle();
        }

        InputImage inputImage = InputImage.fromBitmap(bitmap, 0);

        textRecognizer.process(inputImage)
                .addOnSuccessListener(visionText -> {
                    bitmap.recycle(); // [Perf] OCR 완료 후 즉시 해제
                    processOcrResult(visionText, width, height);
                    // [Fix 2] OCR 완료 즉시 잠금 해제 → 번역 대기 중에도 새 프레임 캡처 가능
                    isProcessing.set(false);
                })
                .addOnFailureListener(e -> {
                    bitmap.recycle();
                    isProcessing.set(false);
                    Log.e(TAG, "OCR 실패: " + e.getMessage());
                });
    }

    private void processOcrResult(Text visionText, int width, int height) {
        List<Text.TextBlock> blocks = visionText.getTextBlocks();
        long currentTime = System.currentTimeMillis();

        // [Fix: 자막 증식] 현재 활성 자막의 화면 점유 영역 스냅샷
        // OCR이 자막 오버레이 자체를 읽어 새 자막을 생성하는 것을 막음
        List<Rect> overlayZones = new ArrayList<>(activeSubtitles.size());
        for (SubtitleItem si : activeSubtitles.values()) {
            if (si.overlayRect != null) overlayZones.add(si.overlayRect);
        }

        for (Text.TextBlock block : blocks) {
            for (Text.Line line : block.getLines()) {
                String text = line.getText().trim();
                Rect rect = line.getBoundingBox();

                if (rect == null || text.isEmpty()) continue;
                if (rect.top < height * 0.08 || rect.bottom > height * 0.95) continue;
                if (text.length() <= 1) continue;
                if (!text.matches(".*[가-힣].*")) continue;

                // [Fix: 자막 증식] 감지된 텍스트 영역이 기존 자막 영역과 겹치면 건너뜀
                boolean inOverlay = false;
                for (Rect zone : overlayZones) {
                    if (Rect.intersects(zone, rect)) {
                        inOverlay = true;
                        break;
                    }
                }
                if (inOverlay) continue;

                String normalizedText = normalizeTextKey(text);
                String existingKey = findMatchingSubtitleKey(normalizedText, rect);
                SubtitleItem existing = existingKey != null ? activeSubtitles.get(existingKey) : null;
                if (existing != null) {
                    existing.lastSeenTime = currentTime;
                    existing.sourceRect = new Rect(rect);
                    continue;
                }

                SubtitleItem newItem = new SubtitleItem();
                newItem.normalizedText = normalizedText;
                newItem.sourceRect = new Rect(rect);
                newItem.lastSeenTime = currentTime;
                String key = buildSubtitleKey(normalizedText);

                if (activeSubtitles.putIfAbsent(key, newItem) != null) continue;

                final Rect capturedRect = new Rect(rect);

                // [Fix: 자막 겹침] X 범위가 겹치는 기존 자막 아래로 위치 조정
                int subtitleTop = capturedRect.bottom;
                for (Rect zone : overlayZones) {
                    boolean xOverlaps = zone.left < capturedRect.right && zone.right > capturedRect.left;
                    boolean yConflicts = subtitleTop < zone.bottom && subtitleTop + SUBTITLE_HEIGHT_PX > zone.top;
                    if (xOverlaps && yConflicts) {
                        subtitleTop = zone.bottom + 2;
                    }
                }
                final int finalSubtitleTop = subtitleTop;

                // 새 자막 영역을 overlayZones에 추가 → 같은 프레임 내 후속 라인도 인식
                newItem.overlayRect = new Rect(capturedRect.left, subtitleTop,
                        capturedRect.right, subtitleTop + SUBTITLE_HEIGHT_PX);
                overlayZones.add(newItem.overlayRect);

                translator.translate(text)
                        .addOnSuccessListener(translatedText -> {
                            mainHandler.post(() -> {
                                if (overlayContainer == null || activeSubtitles.get(key) != newItem) return;

                                TextView tv = new TextView(ScreenCaptureService.this);
                                tv.setText(translatedText);
                                tv.setTextColor(Color.WHITE);
                                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                                tv.setIncludeFontPadding(false);
                                tv.setBackgroundColor(Color.parseColor("#99000000"));
                                tv.setPadding(6, 2, 6, 2);

                                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.WRAP_CONTENT,
                                        FrameLayout.LayoutParams.WRAP_CONTENT
                                );
                                params.leftMargin = capturedRect.left;
                                params.topMargin = finalSubtitleTop; // 겹침 방지 후 확정 위치

                                overlayContainer.addView(tv, params);
                                newItem.view = tv;
                            });
                        })
                        .addOnFailureListener(e -> activeSubtitles.remove(key, newItem));
            }
        }

        // 만료 자막 수집 후 일괄 제거
        List<String> expiredKeys = new ArrayList<>();
        for (Map.Entry<String, SubtitleItem> entry : activeSubtitles.entrySet()) {
            if (currentTime - entry.getValue().lastSeenTime > SUBTITLE_EXPIRY_MS) {
                expiredKeys.add(entry.getKey());
            }
        }
        if (!expiredKeys.isEmpty()) {
            List<TextView> viewsToRemove = new ArrayList<>(expiredKeys.size());
            for (String expiredKey : expiredKeys) {
                SubtitleItem item = activeSubtitles.remove(expiredKey);
                if (item != null && item.view != null) {
                    viewsToRemove.add(item.view);
                    item.view = null;
                }
            }
            if (!viewsToRemove.isEmpty()) {
                mainHandler.post(() -> {
                    if (overlayContainer != null) {
                        for (TextView v : viewsToRemove) overlayContainer.removeView(v);
                    }
                });
            }
        }
    }

    private String normalizeTextKey(String text) {
        return text.replaceAll("\\s+", "");
    }

    private String buildSubtitleKey(String normalizedText) {
        return normalizedText + "#" + subtitleIdGenerator.incrementAndGet();
    }

    private String findMatchingSubtitleKey(String normalizedText, Rect detectedRect) {
        String bestKey = null;
        int bestDistance = Integer.MAX_VALUE;

        for (Map.Entry<String, SubtitleItem> entry : activeSubtitles.entrySet()) {
            SubtitleItem item = entry.getValue();
            if (item == null || item.sourceRect == null) continue;
            if (!normalizedText.equals(item.normalizedText)) continue;

            int distance = centerDistance(item.sourceRect, detectedRect);
            if (!isSameSubtitleRegion(item.sourceRect, detectedRect, distance)) continue;

            if (distance < bestDistance) {
                bestDistance = distance;
                bestKey = entry.getKey();
            }
        }

        return bestKey;
    }

    private boolean isSameSubtitleRegion(Rect existingRect, Rect detectedRect, int centerDistance) {
        int horizontalTolerance = Math.max(MIN_MATCH_DISTANCE_PX,
                Math.max(existingRect.width(), detectedRect.width()) / 3);
        int verticalTolerance = Math.max(MIN_MATCH_DISTANCE_PX + 16,
                Math.max(existingRect.height(), detectedRect.height()) * 2);

        Rect expanded = new Rect(existingRect);
        expanded.inset(-horizontalTolerance, -verticalTolerance);

        return Rect.intersects(expanded, detectedRect)
                || (Math.abs(existingRect.centerX() - detectedRect.centerX()) <= horizontalTolerance
                && Math.abs(existingRect.centerY() - detectedRect.centerY()) <= verticalTolerance
                && centerDistance <= horizontalTolerance + verticalTolerance);
    }

    private int centerDistance(Rect first, Rect second) {
        return Math.abs(first.centerX() - second.centerX())
                + Math.abs(first.centerY() - second.centerY());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Screen Capture Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        teardownCaptureSession();
        if (overlayContainer != null && windowManager != null) {
            try {
                windowManager.removeView(overlayContainer);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Overlay container was already removed.", e);
            }
        }
        overlayContainer = null;
        windowManager = null;
        if (textRecognizer != null) textRecognizer.close();
        if (translator != null) translator.close();
        if (backgroundThread != null) backgroundThread.quitSafely();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
