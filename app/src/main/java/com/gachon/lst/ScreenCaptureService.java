package com.gachon.lst;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;

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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCaptureService";
    private static final String CHANNEL_ID = "ScreenCaptureChannel";

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
    private String lastRecognizedText = "";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🚀 서비스 onCreate() 호출됨");

        // 1. ML Kit 텍스트 인식기 및 번역 엔진 초기화
        textRecognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.KOREAN)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build();
        translator = Translation.getClient(options);

        // 번역 모델 다운로드 상태 확인용 로그 추가
        DownloadConditions conditions = new DownloadConditions.Builder().requireWifi().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(v -> Log.d(TAG, "✅ 한-영 번역 모델 다운로드 및 준비 완료!"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ 번역 모델 다운로드 실패", e));

        // 2. 백그라운드 스레드 시작
        backgroundThread = new HandlerThread("ScreenCaptureThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "🚀 서비스 onStartCommand() 호출됨");

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("실시간 화면 번역기")
                .setContentText("화면을 분석하고 있습니다...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();

        // 안드로이드 14(API 34) 이상을 위한 호환성 코드
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, notification);
        }

        // 💡 수정 1: 기본값을 -1이 아닌 0으로 변경합니다.
        int resultCode = intent.getIntExtra("code", 0);

        // 💡 수정 2: 안드로이드 13 이상을 위한 안전한 데이터 추출 방식 적용
        Intent resultData;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            resultData = intent.getParcelableExtra("data", Intent.class);
        } else {
            resultData = intent.getParcelableExtra("data");
        }

        // 💡 수정 3: resultCode가 Activity.RESULT_OK (즉, -1)인지 정확히 확인합니다.
        if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
            Log.d(TAG, "✅ 권한 데이터 수신 완료. 캡처를 시작합니다.");
            projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
            startScreenCapture();
        } else {
            Log.e(TAG, "❌ 권한 데이터를 제대로 받지 못했습니다! (수신된 code: " + resultCode + ")");
        }

        return START_NOT_STICKY;
    }

    private void startScreenCapture() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        // ImageReader 설정: RGBA_8888 포맷으로 최대 2장의 이미지만 보관
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);

        virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, backgroundHandler);

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    long currentTime = System.currentTimeMillis();
                    // 💡 500ms 간격 + 이전 처리 완료 여부를 함께 확인
                    if (currentTime - lastAnalyzedTimestamp >= 500
                            && isProcessing.compareAndSet(false, true)) {
                        lastAnalyzedTimestamp = currentTime;
                        processImage(image, width, height);
                    } else {
                        image.close(); // 시간 안 지났거나 아직 처리 중이면 버림
                    }
                }
            } catch (Exception e) {
                if (image != null) image.close();
            }
        }, backgroundHandler);
    }

    private void processImage(Image image, int width, int height) {
        // ImageReader의 이미지를 Bitmap으로 변환
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;

        Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        image.close(); // 이미지는 빨리 닫아줘야 메모리 누수가 없습니다.

        // 패딩 잘라내기 (실제 화면 크기에 맞게)
        Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
        InputImage inputImage = InputImage.fromBitmap(croppedBitmap, 0);

        textRecognizer.process(inputImage)
                .addOnSuccessListener(visionText -> {
                    // 전체 인식 텍스트를 합쳐서 이전 결과와 비교
                    StringBuilder sb = new StringBuilder();
                    for (Text.TextBlock block : visionText.getTextBlocks()) {
                        sb.append(block.getText().trim()).append("\n");
                    }
                    String currentText = sb.toString().trim();

                    if (currentText.isEmpty() || currentText.equals(lastRecognizedText)) {
                        // 텍스트가 없거나 이전과 동일하면 번역 스킵
                        Log.d(TAG, "⏭️ 텍스트 변화 없음 — 번역 스킵");
                        isProcessing.set(false);
                        return;
                    }

                    lastRecognizedText = currentText;

                    // 번역할 블록 수를 세어 모든 번역 완료 시 플래그 해제
                    List<Text.TextBlock> blocks = visionText.getTextBlocks();
                    AtomicInteger remaining = new AtomicInteger(blocks.size());

                    for (Text.TextBlock block : blocks) {
                        String text = block.getText();
                        Rect boundingBox = block.getBoundingBox();
                        if (boundingBox != null) {
                            translateText(text, boundingBox, remaining);
                        } else {
                            if (remaining.decrementAndGet() == 0) {
                                isProcessing.set(false);
                            }
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "텍스트 인식 실패", e);
                    isProcessing.set(false);
                });
    }

    private void translateText(String text, Rect boundingBox, AtomicInteger remaining) {
        translator.translate(text)
                .addOnSuccessListener(translatedText -> {
                    Log.d(TAG, "✅ [원문] : " + text.replace("\n", " "));
                    Log.d(TAG, "🎯 [번역] : " + translatedText.replace("\n", " "));
                    Log.d(TAG, "--------------------------------------------------");
                    if (remaining.decrementAndGet() == 0) {
                        isProcessing.set(false);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "번역 실패", e);
                    if (remaining.decrementAndGet() == 0) {
                        isProcessing.set(false);
                    }
                });
    }

    private void createNotificationChannel() {
        // 안드로이드 버전이 8.0(API 26) 이상일 때만 알림 채널을 생성하도록 조건문 추가
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Screen Capture Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (mediaProjection != null) mediaProjection.stop();
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