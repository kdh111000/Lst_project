package com.gachon.lst;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.mlkit.nl.translate.TranslateLanguage;

public class MainActivity extends AppCompatActivity {

    private MediaProjectionManager projectionManager;

    // 💡 새로 추가된 UI (스피너)
    private Spinner spinnerTargetLang;

    // 💡 번역할 언어 코드 배열 (스피너 메뉴 순서와 똑같이 맞춥니다)
    private final String[] targetLanguageCodes = {
            TranslateLanguage.ENGLISH,   // 0: 영어
            TranslateLanguage.JAPANESE,  // 1: 일본어
            TranslateLanguage.CHINESE,   // 2: 중국어
            TranslateLanguage.SPANISH    // 3: 스페인어
    };

    // 화면 캡처 권한을 요청하고 결과를 받아오는 런처
    private final ActivityResultLauncher<Intent> screenCaptureLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {

                    Toast.makeText(this, "실시간 화면 번역을 시작합니다!", Toast.LENGTH_SHORT).show();

                    // 서비스 실행용 Intent 생성
                    Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
                    serviceIntent.putExtra("code", result.getResultCode());
                    serviceIntent.putExtra("data", result.getData());

                    // 💡 핵심: 사용자가 스피너에서 무슨 언어를 골랐는지 확인하고 같이 택배로 보냅니다!
                    int selectedLangIndex = spinnerTargetLang.getSelectedItemPosition();
                    serviceIntent.putExtra("targetLang", targetLanguageCodes[selectedLangIndex]);

                    // 포그라운드 서비스 실행
                    ContextCompat.startForegroundService(this, serviceIntent);

                    // 앱 화면을 닫고 바탕화면으로 이동시킴
                    moveTaskToBack(true);
                } else {
                    Toast.makeText(this, "화면 캡처 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // 방금 올려주신 멋진 XML 적용!

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        // 💡 XML에 있는 버튼과 스피너 아이디(ID)를 찾아서 연결합니다.
        spinnerTargetLang = findViewById(R.id.spinnerTargetLang);
        Button btnTranslate = findViewById(R.id.btnTranslate);

        // 스피너에 "영어, 일본어, 중국어, 스페인어" 메뉴를 채워 넣습니다.
        String[] displayLanguages = {"영어 (English)", "일본어 (日本語)", "중국어 (中文)", "스페인어 (Español)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                displayLanguages
        );
        spinnerTargetLang.setAdapter(adapter);

        // UI에 있는 "Translate" 버튼을 캡처 시작 버튼으로 사용합니다.
        btnTranslate.setText("캡처 번역 시작");
        btnTranslate.setOnClickListener(v -> checkPermissionsAndStart());
    }

    private void checkPermissionsAndStart() {
        // 1. '다른 앱 위에 그리기(오버레이)' 권한이 있는지 확인
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "자막창을 띄우려면 '다른 앱 위에 표시' 권한이 필요합니다.", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }

        // 2. 오버레이 권한이 있다면, 화면 캡처(녹화) 권한을 요청
        if (projectionManager != null) {
            Intent captureIntent = projectionManager.createScreenCaptureIntent();
            screenCaptureLauncher.launch(captureIntent);
        }
    }
}