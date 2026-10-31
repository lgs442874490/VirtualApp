package com.carlos.vault;

/**
 * 纯 Java 的响应式布局规则。只计算尺寸，不持有 View 或业务状态，便于单元测试。
 *
 * <p>约束：手机左右边距固定 18dp；宽屏内容居中且不超过 640dp；
 * 网格列数由可用宽度计算，不写死设备型号。</p>
 */
public final class UiLayoutRules {
    public static final int SCREEN_MARGIN_DP = 18;
    public static final int MAX_CONTENT_WIDTH_DP = 640;

    private UiLayoutRules() {
    }

    /** 返回内容区宽度（px）。availableWidthPx 为当前窗口可用宽度，而非物理屏幕宽度。 */
    public static int contentWidthPx(int availableWidthPx, float density) {
        return contentWidthPx(availableWidthPx, density, MAX_CONTENT_WIDTH_DP);
    }

    /** 返回内容区宽度（px）；maxContentWidthDp 必须大于 0。 */
    public static int contentWidthPx(int availableWidthPx, float density, int maxContentWidthDp) {
        return contentWidthPx(availableWidthPx, density, maxContentWidthDp, SCREEN_MARGIN_DP);
    }

    /** 特殊弹窗可复用同一边距/限宽算法，但保留自身设计稿的最大宽度。 */
    public static int contentWidthPx(int availableWidthPx, float density,
                                     int maxContentWidthDp, int marginDp) {
        if (availableWidthPx <= 0 || density <= 0f || maxContentWidthDp <= 0) return 0;
        int margin = Math.round(Math.max(0, marginDp) * density);
        int availableContent = Math.max(0, availableWidthPx - margin * 2);
        int maxContent = Math.round(maxContentWidthDp * density);
        return Math.min(availableContent, maxContent);
    }

    /**
     * 按最小单元宽计算网格列数。结果始终为 1..maxColumns；
     * 不能容纳一个完整单元时仍返回 1，由调用方让单元拉伸，避免横向溢出。
     */
    public static int columns(int availableWidthDp, int minCellWidthDp, int gapDp, int maxColumns) {
        if (maxColumns <= 1) return 1;
        if (availableWidthDp <= 0 || minCellWidthDp <= 0) return 1;
        int safeGap = Math.max(0, gapDp);
        int count = 1;
        for (int candidate = 2; candidate <= maxColumns; candidate++) {
            int required = candidate * minCellWidthDp + (candidate - 1) * safeGap;
            if (required > availableWidthDp) break;
            count = candidate;
        }
        return count;
    }
}
