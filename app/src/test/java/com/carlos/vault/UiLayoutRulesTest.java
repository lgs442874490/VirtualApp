package com.carlos.vault;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 响应式规则纯 JVM 回归：手机、窄屏、平板和网格边界。 */
public class UiLayoutRulesTest {
    @Test
    public void phoneKeepsEighteenDpMargins() {
        assertEquals(364, UiLayoutRules.contentWidthPx(400, 1f));
    }

    @Test
    public void wideScreenCapsAndCentersContent() {
        assertEquals(640, UiLayoutRules.contentWidthPx(1200, 1f));
        assertEquals(1280, UiLayoutRules.contentWidthPx(2400, 2f));
    }

    @Test
    public void splitScreenNeverProducesNegativeWidth() {
        assertEquals(0, UiLayoutRules.contentWidthPx(20, 1f));
        assertEquals(0, UiLayoutRules.contentWidthPx(0, 1f));
        assertEquals(0, UiLayoutRules.contentWidthPx(400, 0f));
    }

    @Test
    public void specialDialogKeepsOwnMaxWidthAndMargins() {
        assertEquals(463, UiLayoutRules.contentWidthPx(1200, 1f, 463, 12));
        assertEquals(376, UiLayoutRules.contentWidthPx(400, 1f, 463, 12));
        assertEquals(0, UiLayoutRules.contentWidthPx(20, 1f, 463, 12));
    }

    @Test
    public void bottomSheetStaysFullWidthOnPhoneAndCapsOnWideScreen() {
        assertEquals(400, UiLayoutRules.contentWidthPx(400, 1f, 640, 0));
        assertEquals(640, UiLayoutRules.contentWidthPx(1200, 1f, 640, 0));
        assertEquals(1280, UiLayoutRules.contentWidthPx(2400, 2f, 640, 0));
    }

    @Test
    public void gridUsesAvailableWidthAndGap() {
        assertEquals(1, UiLayoutRules.columns(100, 120, 8, 4));
        assertEquals(2, UiLayoutRules.columns(248, 120, 8, 4));
        assertEquals(3, UiLayoutRules.columns(376, 120, 8, 4));
        assertEquals(4, UiLayoutRules.columns(900, 120, 8, 4));
    }

    @Test
    public void invalidGridInputsDegradeToSingleColumn() {
        assertEquals(1, UiLayoutRules.columns(0, 120, 8, 4));
        assertEquals(1, UiLayoutRules.columns(400, 0, 8, 4));
        assertEquals(1, UiLayoutRules.columns(400, 120, 8, 0));
    }
}
