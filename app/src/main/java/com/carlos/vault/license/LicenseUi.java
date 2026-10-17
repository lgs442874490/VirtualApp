package com.carlos.vault.license;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.carlos.vault.UiStyle;

/**
 * 授权相关界面：激活页 / 锁定页 / 主界面顶部剩余天数条。
 *
 * 全部用代码构建 View，复用工程既有的 {@link UiStyle}（无 XML 布局、无 Material 组件），
 * 与 {@code AccountVaultActivity} 的既有风格保持一致。
 */
public final class LicenseUi {

    /** 激活成功后回调，由宿主页面据此重建主界面。 */
    public interface OnChanged {
        void onChanged();
    }

    private LicenseUi() {
    }

    private static int dp(Context ctx, int v) {
        return (int) (ctx.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    // ---------- 门禁入口 ----------

    /**
     * 按当前状态渲染门禁页：未激活/被篡改 → 激活页；其余失效态 → 锁定页。
     * 调用方（AccountVaultActivity）应直接 setContentView 本返回值并 return。
     */
    public static View buildGateView(Activity act, LicenseManager lm, final OnChanged onChanged) {
        lm.ensureLoaded();
        LicenseState st = lm.recheckLocalState();
        if (st == LicenseState.UNACTIVATED || st == LicenseState.TAMPERED) {
            return buildActivateView(act, lm, onChanged);
        }
        return buildLockView(act, lm, st, onChanged);
    }

    // ---------- 激活页（PRD 4.1） ----------

    public static View buildActivateView(final Activity act, final LicenseManager lm,
                                         final OnChanged onChanged) {
        LinearLayout root = pageRoot(act);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        // 品牌 Logo
        android.widget.FrameLayout logo = new android.widget.FrameLayout(act);
        GradientDrawable logoBg = new GradientDrawable();
        logoBg.setColor(UiStyle.BRAND);
        logoBg.setCornerRadius(dp(act, 28));
        logo.setBackground(logoBg);
        logo.addView(new UiStyle.Icon(act, UiStyle.Icon.SNOW, UiStyle.WHITE, 52),
                new android.widget.FrameLayout.LayoutParams(dp(act, 52), dp(act, 52),
                        Gravity.CENTER));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(act, 96), dp(act, 96));
        logoLp.topMargin = dp(act, 54);
        logoLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(logo, logoLp);

        TextView title = new TextView(act);
        title.setText("无尽号库");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        title.setGravity(Gravity.CENTER);
        root.addView(title, marginTop(act, 18));

        TextView subtitle = new TextView(act);
        subtitle.setText("账号库 · 自动日常 · 多开容器");
        subtitle.setTextSize(13);
        subtitle.setTextColor(UiStyle.INK2);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, marginTop(act, 6));

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiStyle.roundRectStroke(act, UiStyle.WHITE, 20, UiStyle.LINE));
        card.setPadding(dp(act, 20), dp(act, 20), dp(act, 20), dp(act, 20));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(dp(act, 18), dp(act, 26), dp(act, 18), 0);
        card.setLayoutParams(cardLp);

        TextView label = new TextView(act);
        label.setText("授权码 / 兑换码");
        label.setTextSize(12);
        label.setTextColor(UiStyle.INK2);
        card.addView(label);

        final EditText input = new EditText(act);
        input.setHint("WJDR-XXXX-XXXX-XXXX");
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setTextColor(UiStyle.INK);
        input.setHintTextColor(UiStyle.INK3);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setBackground(UiStyle.roundRectStroke(act, UiStyle.CARD, 12, UiStyle.LINE_STRONG));
        input.setPadding(dp(act, 14), dp(act, 14), dp(act, 14), dp(act, 14));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(act, 8);
        card.addView(input, inputLp);

        final TextView error = new TextView(act);
        error.setText("⚠ 未激活 · 激活后自动绑定本机");
        error.setTextSize(12);
        error.setTextColor(UiStyle.WARN_INK);
        error.setPadding(dp(act, 2), dp(act, 12), dp(act, 2), 0);
        card.addView(error);

        final TextView submit = UiStyle.primaryBtn(act, "激活授权", null);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 52));
        slp.topMargin = dp(act, 14);
        card.addView(submit, slp);

        View div = new View(act);
        div.setBackgroundColor(UiStyle.LINE);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 1));
        dlp.topMargin = dp(act, 18);
        card.addView(div, dlp);

        String[] features = {
                "一码一设备 · 首次激活即绑定，防复用",
                "支持按月 / 永久 · 服务端可随时吊销",
        };
        for (String f : features) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(act, 10), 0, 0);
            row.addView(new UiStyle.Icon(act, UiStyle.Icon.CHECK, UiStyle.OK_INK, 16),
                    new LinearLayout.LayoutParams(dp(act, 16), dp(act, 16)));
            TextView tx = new TextView(act);
            tx.setText(f);
            tx.setTextSize(12);
            tx.setTextColor(UiStyle.INK2);
            tx.setPadding(dp(act, 8), 0, 0, 0);
            row.addView(tx);
            card.addView(row);
        }
        root.addView(card);

        String fp = "";
        try {
            String h = DeviceId.withSalt(act, "ardot-display");
            fp = h.length() >= 12
                    ? h.substring(0, 4) + "-" + h.substring(4, 8) + "-" + h.substring(8, 12)
                    : h;
        } catch (Throwable ignored) {
        }
        TextView footer = new TextView(act);
        footer.setText("设备指纹 " + (fp.isEmpty() ? "—" : fp)
                + " · 购买 / 续期请联系上级代理");
        footer.setTextSize(11);
        footer.setTextColor(UiStyle.INK3);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, marginTop(act, 18));

        final ProgressDialog[] holder = new ProgressDialog[1];

        UiStyle.press(submit);
        submit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String code = LicenseManager.normalize(input.getText().toString());
                if (code.length() != 16) {
                    showError(error, "兑换码不存在或格式错误");
                    return;
                }
                error.setVisibility(View.GONE);
                submit.setEnabled(false);
                submit.setText("正在激活…");

                ProgressDialog pd = new ProgressDialog(act);
                pd.setMessage("正在激活…");
                pd.setCancelable(false);
                pd.show();
                holder[0] = pd;

                lm.activateAsync(code, new LicenseManager.Callback() {
                    @Override
                    public void onResult(LicenseState state, String message) {
                        if (holder[0] != null) {
                            try {
                                holder[0].dismiss();
                            } catch (Throwable ignored) {
                            }
                        }
                        submit.setEnabled(true);
                        submit.setText("激活授权");
                        if (state != null && state.usable()) {
                            UiStyle.toast(act, message);
                            if (onChanged != null) onChanged.onChanged();
                        } else {
                            showError(error, message == null ? "激活失败" : message);
                        }
                    }
                });
            }
        });

        return wrapScroll(root);
    }

    // ---------- 锁定页（PRD 4.2） ----------

    public static View buildLockView(final Activity act, final LicenseManager lm,
                                     final LicenseState st, final OnChanged onChanged) {
        LinearLayout root = pageRoot(act);
        root.addView(titleBar(act, "⛔ 使用受限"));

        LinearLayout card = UiStyle.card(act);
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView icon = new TextView(act);
        icon.setText(lockIcon(st));
        icon.setTextSize(44);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon, marginTop(act, 8));

        TextView title = new TextView(act);
        title.setText(st == null ? "使用受限" : st.title());
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiStyle.INK);
        title.setGravity(Gravity.CENTER);
        card.addView(title, marginTop(act, 12));

        TextView desc = new TextView(act);
        desc.setText(descriptionFor(lm, st));
        desc.setTextSize(14);
        desc.setTextColor(UiStyle.INK2);
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(dp(act, 3), 1f);
        card.addView(desc, marginTop(act, 10));

        // 离线超期是可恢复态：给一个显式的「联网重试」
        if (st == LicenseState.OFFLINE_EXPIRED) {
            final TextView retry = UiStyle.primaryBtn(act, "联网重试", null);
            card.addView(retry, marginTop(act, 18));
            UiStyle.press(retry);
            retry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final ProgressDialog pd = new ProgressDialog(act);
                    pd.setMessage("正在联网校验…");
                    pd.setCancelable(false);
                    pd.show();
                    lm.verifyAsync(new LicenseManager.Callback() {
                        @Override
                        public void onResult(LicenseState state, String message) {
                            try {
                                pd.dismiss();
                            } catch (Throwable ignored) {
                            }
                            UiStyle.toast(act, message);
                            if (state != null && state.usable() && onChanged != null) {
                                onChanged.onChanged();
                            }
                        }
                    });
                }
            });
        }

        // 终态也可换新码：给出重新激活入口
        if (st == LicenseState.EXPIRED || st == LicenseState.REVOKED
                || st == LicenseState.TAMPERED || st == LicenseState.UNACTIVATED) {
            TextView reenter = UiStyle.secondaryBtn(act, "重新输入兑换码激活", null);
            LinearLayout.LayoutParams reenterLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiStyle.dp(act, 40));
            reenterLp.topMargin = dp(act, 18);
            card.addView(reenter, reenterLp);
            UiStyle.press(reenter);
            reenter.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showActivateDialog(act, lm, onChanged);
                }
            });
        }

        TextView contact = UiStyle.ghostBtn(act, "联系客服", null);
        card.addView(contact, marginTop(act, 10));
        UiStyle.press(contact);
        contact.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiStyle.toast(act, "请通过购买渠道联系运营方");
            }
        });

        root.addView(card);
        return wrapScroll(root);
    }

    private static String lockIcon(LicenseState st) {
        if (st == null) return "⛔";
        switch (st) {
            case REVOKED:
                return "🚫";
            case DEVICE_MISMATCH:
                return "📱";
            case EXPIRED:
                return "⏳";
            case OFFLINE_EXPIRED:
                return "🌐";
            default:
                return "⛔";
        }
    }

    /** 锁定页说明文案（PRD 4.2 文案表）。 */
    public static String descriptionFor(LicenseManager lm, LicenseState st) {
        if (st == null) return "请使用兑换码激活本应用。";
        switch (st) {
            case REVOKED:
                return "您的兑换码已被停用，无法继续使用。如有疑问请联系客服。";
            case DEVICE_MISMATCH:
                return "该兑换码已绑定其他设备，本机无法使用。换机请联系客服换绑。";
            case EXPIRED: {
                String date = lm == null ? "" : lm.expireDateText();
                return "您的使用授权已于 " + (date.isEmpty() ? "到期日" : date)
                        + " 到期，请续费后继续使用。";
            }
            case OFFLINE_EXPIRED:
                return "已超过 " + LicenseConfig.GRACE_DAYS + " 天未联网校验授权，请联网后重试。";
            case TAMPERED:
                return "授权数据异常，请重新输入兑换码激活。";
            case UNACTIVATED:
            default:
                return "请使用兑换码激活本应用。";
        }
    }

    // ---------- 主界面顶部剩余天数条（PRD 4.3） ----------

    public static View buildStatusBar(Activity act, LicenseManager lm) {
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(act, 16), dp(act, 10), dp(act, 16), dp(act, 10));

        LicenseState st = lm.recheckLocalState();
        bar.setBackground(UiStyle.roundRect(barColor(st), 10));

        TextView tv = new TextView(act);
        tv.setText(lm.describeRemain());
        tv.setTextSize(13);
        tv.setTextColor(barTextColor(st));
        if (st == LicenseState.ACTIVE && lm.remainDays() >= 0 && lm.remainDays() <= 7) {
            tv.setTextColor(UiStyle.WARN_INK);
        }
        bar.addView(tv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        String date = lm.expireDateText();
        if (!date.isEmpty()) {
            TextView sub = new TextView(act);
            sub.setText("至 " + date);
            sub.setTextSize(12);
            sub.setTextColor(UiStyle.INK2);
            bar.addView(sub);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(act, 16), dp(act, 10), dp(act, 16), 0);
        bar.setLayoutParams(lp);
        return bar;
    }

    private static int barColor(LicenseState st) {
        if (st == null) return UiStyle.IDLE_BG;
        switch (st) {
            case ACTIVE:
                return UiStyle.WAIT_BG;
            case ACTIVE_OFFLINE:
                return UiStyle.WARN_BG;
            default:
                return UiStyle.ERR_BG;
        }
    }

    private static int barTextColor(LicenseState st) {
        if (st == null) return UiStyle.INK2;
        switch (st) {
            case ACTIVE:
                return UiStyle.BRAND;
            case ACTIVE_OFFLINE:
                return UiStyle.WARN_INK;
            default:
                return UiStyle.ERR_INK;
        }
    }

    // ---------- 头部授权状态圆环（替换原文字状态条） ----------

    /** 环形颜色（PRD：>3天绿 / ≤3天黄 / 过期·废除红 / 永久绿满 / 离线灰）。 */
    private static final int RING_GREEN = UiStyle.OK_SOLID;
    private static final int RING_YELLOW = UiStyle.WARN_SOLID;
    private static final int RING_RED = UiStyle.ERR_SOLID;
    private static final int RING_GRAY = UiStyle.INK3;
    private static final int RING_TRACK = UiStyle.LINE;

    /**
     * 构建头部授权圆环（直径 34dp）。点击行为由调用方挂（直达授权管理）。
     * 数据源 {@link LicenseManager}：百分比 = 剩余时长 ÷ 票据总时长。
     */
    public static LicenseRingView buildRing(Context ctx, LicenseManager lm) {
        LicenseRingView ring = new LicenseRingView(ctx);
        ring.updateFrom(lm);
        return ring;
    }

    /** 自绘授权状态环：底环 + 进度弧 + 环心文字（剩余天数 / ∞ / 0 / 废）。 */
    public static class LicenseRingView extends View {

        private float fraction = 1f;      // 进度 0~1
        private int ringColor = RING_GRAY;
        private String centerText = "…";

        public LicenseRingView(Context ctx) {
            super(ctx);
        }

        /** 依据授权管理器重算颜色 / 进度 / 环心文字。 */
        public void updateFrom(LicenseManager lm) {
            if (lm == null) { invalidate(); return; }
            LicenseState st = lm.recheckLocalState();
            LicenseTicket t = lm.currentTicket();
            boolean permanent = t != null && t.isPermanent();

            if (permanent && (st == LicenseState.ACTIVE || st == LicenseState.ACTIVE_OFFLINE)) {
                ringColor = RING_GREEN;
                fraction = 1f;
                centerText = "∞";
            } else if (st == LicenseState.REVOKED) {
                ringColor = RING_RED;
                fraction = 1f;
                centerText = "废";
            } else if (st == LicenseState.EXPIRED || st == LicenseState.OFFLINE_EXPIRED
                    || st == LicenseState.DEVICE_MISMATCH || st == LicenseState.TAMPERED
                    || st == LicenseState.UNACTIVATED) {
                // 过期/设备不匹配等终态：红色满环（离线超宽限期按灰，可联网恢复）
                ringColor = st == LicenseState.OFFLINE_EXPIRED ? RING_GRAY : RING_RED;
                fraction = 1f;
                centerText = st == LicenseState.OFFLINE_EXPIRED ? "…" : "0";
            } else {
                // 可用态：按剩余时长百分比；≤3 天转黄
                int remain = lm.remainDays();
                long totalMs = (t != null && t.activatedAt > 0 && t.expireAt > t.activatedAt)
                        ? (t.expireAt - t.activatedAt) : 0L;
                if (totalMs > 0) {
                    long remainMs = Math.max(0L, t.expireAt - System.currentTimeMillis());
                    fraction = Math.max(0.04f, Math.min(1f, remainMs / (float) totalMs));
                } else {
                    fraction = 1f;   // 拿不到总时长时不瞎猜，满环但颜色表达状态
                }
                ringColor = (remain >= 0 && remain <= 3) ? RING_YELLOW : RING_GREEN;
                centerText = String.valueOf(Math.max(0, remain));
            }
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float stroke = Math.max(4f, Math.min(w, h) * 0.11f);
            float radius = (Math.min(w, h) - stroke) / 2f - 1f;

            android.graphics.Paint track = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            track.setStyle(android.graphics.Paint.Style.STROKE);
            track.setStrokeWidth(stroke);
            track.setColor(RING_TRACK);
            canvas.drawCircle(cx, cy, radius, track);

            android.graphics.Paint arc = new android.graphics.Paint(track);
            arc.setColor(ringColor);
            arc.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            android.graphics.RectF box = new android.graphics.RectF(
                    cx - radius, cy - radius, cx + radius, cy + radius);
            float sweep = 360f * Math.max(0f, Math.min(1f, fraction));
            if (sweep > 0f) canvas.drawArc(box, -90f, sweep, false, arc);

            android.graphics.Paint tp = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            tp.setColor(ringColor);
            tp.setTypeface(Typeface.DEFAULT_BOLD);
            tp.setTextSize(Math.min(w, h) * 0.34f);
            tp.setTextAlign(android.graphics.Paint.Align.CENTER);
            float ty = cy - (tp.descent() + tp.ascent()) / 2f;
            canvas.drawText(centerText, cx, ty, tp);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int size = dp(getContext(), 34);
            setMeasuredDimension(size, size);
        }
    }

    // ---------- 重新输入兑换码 ----------

    /** 弹窗式重新激活入口（锁定页与主界面「重新输入兑换码」共用）。 */
    public static void showActivateDialog(final Activity act, final LicenseManager lm,
                                          final OnChanged onChanged) {
        UiStyle.showInputDialog(act, "输入兑换码", "XXXX-XXXX-XXXX-XXXX", "", false,
                new UiStyle.OnInputResult() {
                    @Override
                    public void onResult(String value) {
                        final String code = LicenseManager.normalize(value);
                        if (code.length() != 16) {
                            UiStyle.toast(act, "兑换码不存在或格式错误");
                            return;
                        }
                        final ProgressDialog pd = new ProgressDialog(act);
                        pd.setMessage("正在激活…");
                        pd.setCancelable(false);
                        pd.show();
                        lm.activateAsync(code, new LicenseManager.Callback() {
                            @Override
                            public void onResult(LicenseState state, String message) {
                                try {
                                    pd.dismiss();
                                } catch (Throwable ignored) {
                                }
                                UiStyle.toast(act, message);
                                if (state != null && state.usable() && onChanged != null) {
                                    onChanged.onChanged();
                                }
                            }
                        });
                    }
                });
    }

    // ---------- 复用件 ----------

    private static LinearLayout pageRoot(Activity act) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiStyle.BG_V2);
        root.setPadding(0, 0, 0, dp(act, 28));
        return root;
    }

    private static View wrapScroll(LinearLayout content) {
        ScrollView sv = new ScrollView(content.getContext());
        sv.setBackgroundColor(UiStyle.BG_V2);
        sv.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private static View titleBar(Activity act, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(19);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(UiStyle.BRAND_TEXT);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(act, 20), dp(act, 32), dp(act, 8), dp(act, 8));
        return tv;
    }

    private static LinearLayout.LayoutParams marginTop(Activity act, int dpValue) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(act, dpValue);
        return lp;
    }

    private static void showError(TextView error, String msg) {
        error.setText(msg);
        error.setVisibility(View.VISIBLE);
    }
}
