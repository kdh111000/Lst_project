package com.gachon.lst;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 카메라 프리뷰 위에 그래픽을 그리기 위한 뷰
 */
public class GraphicOverlay extends View {
    private final Object lock = new Object();
    private final List<Graphic> graphics = new ArrayList<>();

    public GraphicOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * 그래픽 오버레이에서 모든 그래픽 제거
     */
    public void clear() {
        synchronized (lock) {
            graphics.clear();
        }
        postInvalidate();
    }

    /**
     * 그래픽 추가
     */
    public void add(Graphic graphic) {
        synchronized (lock) {
            graphics.add(graphic);
        }
    }

    /**
     * 그래픽 제거
     */
    public void remove(Graphic graphic) {
        synchronized (lock) {
            graphics.remove(graphic);
        }
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        synchronized (lock) {
            for (Graphic graphic : graphics) {
                graphic.draw(canvas);
            }
        }
    }

    /**
     * 오버레이에 그려질 그래픽의 기본 클래스
     */
    public abstract static class Graphic {
        private GraphicOverlay overlay;

        public Graphic(GraphicOverlay overlay) {
            this.overlay = overlay;
        }

        /**
         * 캔버스에 그래픽 그리기
         */
        public abstract void draw(Canvas canvas);

        /**
         * 화면 좌표계에서 x 좌표 계산
         */
        public float scaleX(float imageX) {
            return imageX * overlay.getWidth();
        }

        /**
         * 화면 좌표계에서 y 좌표 계산
         */
        public float scaleY(float imageY) {
            return imageY * overlay.getHeight();
        }

        /**
         * 이미지 좌표를 뷰 좌표로 변환
         */
        public float translateX(float x) {
            return scaleX(x);
        }

        /**
         * 이미지 좌표를 뷰 좌표로 변환
         */
        public float translateY(float y) {
            return scaleY(y);
        }

        public void postInvalidate() {
            overlay.postInvalidate();
        }
    }
}
