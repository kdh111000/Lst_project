package com.gachon.lst;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "RealTimeTranslator";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private PreviewView previewView;
    private GraphicOverlay graphicOverlay;
    private Spinner sourceLanguageSpinner;
    private Spinner targetLanguageSpinner;

    private TextRecognizer textRecognizer;
    private Translator translator;
    private ExecutorService cameraExecutor;

    private String sourceLanguage = TranslateLanguage.KOREAN;
    private String targetLanguage = TranslateLanguage.ENGLISH;

    // 언어 맵
    private Map<String, String> languageMap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 뷰 초기화
        previewView = findViewById(R.id.previewView);
        graphicOverlay = findViewById(R.id.graphicOverlay);
        sourceLanguageSpinner = findViewById(R.id.sourceLanguageSpinner);
        targetLanguageSpinner = findViewById(R.id.targetLanguageSpinner);

        // 언어 맵 초기화
        initLanguageMap();

        // 스피너 설정
        setupSpinners();

        // 텍스트 인식기 초기화 (한국어 지원)
        textRecognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());

        // 카메라 실행자 초기화
        cameraExecutor = Executors.newSingleThreadExecutor();

        // 권한 확인 및 요청
        if (checkPermissions()) {
            startCamera();
        } else {
            requestPermissions();
        }

        // 번역기 초기화
        initTranslator();
    }

    private void initLanguageMap() {
        languageMap = new HashMap<>();
        languageMap.put("한국어", TranslateLanguage.KOREAN);
        languageMap.put("영어", TranslateLanguage.ENGLISH);
        languageMap.put("일본어", TranslateLanguage.JAPANESE);
        languageMap.put("중국어(간체)", TranslateLanguage.CHINESE);
        languageMap.put("스페인어", TranslateLanguage.SPANISH);
        languageMap.put("프랑스어", TranslateLanguage.FRENCH);
        languageMap.put("독일어", TranslateLanguage.GERMAN);
        languageMap.put("러시아어", TranslateLanguage.RUSSIAN);
        languageMap.put("이탈리아어", TranslateLanguage.ITALIAN);
        languageMap.put("포르투갈어", TranslateLanguage.PORTUGUESE);
    }

    private void setupSpinners() {
        String[] languages = languageMap.keySet().toArray(new String[0]);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, languages);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        sourceLanguageSpinner.setAdapter(adapter);
        targetLanguageSpinner.setAdapter(adapter);

        // 기본값 설정 (한국어 -> 영어)
        sourceLanguageSpinner.setSelection(0);
        targetLanguageSpinner.setSelection(1);

        sourceLanguageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = (String) parent.getItemAtPosition(position);
                sourceLanguage = languageMap.get(selected);
                initTranslator();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        targetLanguageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = (String) parent.getItemAtPosition(position);
                targetLanguage = languageMap.get(selected);
                initTranslator();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void initTranslator() {
        if (translator != null) {
            translator.close();
        }

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build();

        translator = Translation.getClient(options);

        // 모델 다운로드
        DownloadConditions conditions = new DownloadConditions.Builder()
                .requireWifi()
                .build();

        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    Toast.makeText(this, "번역 모델 준비 완료", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "번역 모델 다운로드 실패", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "모델 다운로드 실패", e);
                });
    }

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA},
                PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "카메라 권한이 필요합니다", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "카메라 초기화 실패", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(ProcessCameraProvider cameraProvider) {
        // Preview
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // Image Analysis
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
            @Override
            public void analyze(@NonNull ImageProxy imageProxy) {
                processImage(imageProxy);
            }
        });

        // Camera Selector
        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
            );
        } catch (Exception e) {
            Log.e(TAG, "카메라 바인딩 실패", e);
        }
    }

    private void processImage(ImageProxy imageProxy) {
        @androidx.camera.core.ExperimentalGetImage
        android.media.Image mediaImage = imageProxy.getImage();

        if (mediaImage != null) {
            InputImage image = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.getImageInfo().getRotationDegrees()
            );

            textRecognizer.process(image)
                    .addOnSuccessListener(visionText -> {
                        graphicOverlay.clear();
                        processTextRecognitionResult(visionText);
                        imageProxy.close();
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "텍스트 인식 실패", e);
                        imageProxy.close();
                    });
        } else {
            imageProxy.close();
        }
    }

    private void processTextRecognitionResult(Text visionText) {
        for (Text.TextBlock block : visionText.getTextBlocks()) {
            String blockText = block.getText();
            Rect blockFrame = block.getBoundingBox();

            if (blockFrame != null) {
                // 각 텍스트 블록을 번역
                translateText(blockText, blockFrame);
            }
        }
    }

    private void translateText(String text, Rect boundingBox) {
        if (translator == null) return;

        translator.translate(text)
                .addOnSuccessListener(translatedText -> {
                    runOnUiThread(() -> {
                        TextGraphic graphic = new TextGraphic(
                                graphicOverlay,
                                text,
                                translatedText,
                                boundingBox
                        );
                        graphicOverlay.add(graphic);
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "번역 실패: " + text, e);
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (textRecognizer != null) {
            textRecognizer.close();
        }
        if (translator != null) {
            translator.close();
        }
    }

    // 텍스트 그래픽 클래스
    private static class TextGraphic extends GraphicOverlay.Graphic {
        private static final int TEXT_COLOR = Color.WHITE;
        private static final int BACKGROUND_COLOR = Color.parseColor("#88000000");
        private static final float TEXT_SIZE = 40.0f;
        private static final float STROKE_WIDTH = 4.0f;

        private final Paint rectPaint;
        private final Paint textPaint;
        private final Paint backgroundPaint;
        private final String originalText;
        private final String translatedText;
        private final Rect boundingBox;

        TextGraphic(GraphicOverlay overlay, String originalText, String translatedText, Rect boundingBox) {
            super(overlay);
            this.originalText = originalText;
            this.translatedText = translatedText;
            this.boundingBox = boundingBox;

            rectPaint = new Paint();
            rectPaint.setColor(Color.GREEN);
            rectPaint.setStyle(Paint.Style.STROKE);
            rectPaint.setStrokeWidth(STROKE_WIDTH);

            textPaint = new Paint();
            textPaint.setColor(TEXT_COLOR);
            textPaint.setTextSize(TEXT_SIZE);
            textPaint.setAntiAlias(true);

            backgroundPaint = new Paint();
            backgroundPaint.setColor(BACKGROUND_COLOR);
            backgroundPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void draw(Canvas canvas) {
            if (boundingBox == null) return;

            // 경계 상자 그리기
            RectF rect = new RectF(boundingBox);
            canvas.drawRect(rect, rectPaint);

            // 번역된 텍스트 배경
            float textWidth = textPaint.measureText(translatedText);
            RectF textBackground = new RectF(
                    rect.left,
                    rect.bottom,
                    rect.left + textWidth + 20,
                    rect.bottom + TEXT_SIZE + 20
            );
            canvas.drawRect(textBackground, backgroundPaint);

            // 번역된 텍스트 그리기
            canvas.drawText(
                    translatedText,
                    rect.left + 10,
                    rect.bottom + TEXT_SIZE + 5,
                    textPaint
            );
        }
    }
}
