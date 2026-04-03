package com.gachon.lst;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private MediaProjectionManager projectionManager;

    // 💡 최신 방식: 화면 캡처 권한을 요청하고 결과를 받아오는 런처
    private final ActivityResultLauncher<Intent> screenCaptureLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {

                    Toast.makeText(this, "번역기가 실행되었습니다. 앱을 내려도 동작합니다.", Toast.LENGTH_SHORT).show();

                    // 권한을 허락받았다면, 백그라운드 서비스(ScreenCaptureService)를 실행!
                    Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
                    serviceIntent.putExtra("code", result.getResultCode());
                    serviceIntent.putExtra("data", result.getData());

                    // 안드로이드 버전에 맞게 포그라운드 서비스 실행
                    ContextCompat.startForegroundService(this, serviceIntent);

                    // 앱 화면을 닫고 홈 화면으로 이동시킴
                    moveTaskToBack(true);
                } else {
                    Toast.makeText(this, "화면 캡처 권한이 거부되었습니다.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Button btnStart = findViewById(R.id.btn_start_translation);

        btnStart.setOnClickListener(v -> checkPermissionsAndStart());
    }

    private void checkPermissionsAndStart() {
        // 1. '다른 앱 위에 그리기(오버레이)' 권한이 있는지 확인
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "'다른 앱 위에 표시' 권한을 허용해 주세요.", Toast.LENGTH_LONG).show();
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