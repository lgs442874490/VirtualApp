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

        root.addView(titleBar(act, "无尽号库 · 激活"));

        LinearLayout card = UiStyle.card(act);
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView icon = new TextView(act);
        icon.setText("🔑");
        icon.setTextSize(44);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon, marginTop(act, 8));

        TextView intro = new TextView(act);
        intro.setText("请使用兑换码激活本应用");
        intro.setTextSize(15);
        intro.setTypeface(Typeface.DEFAULT_BOLD);
        intro.setTextColor(UiStyle.TEXT_PRI);
        intro.setGravity(Gravity.CENTER);
        card.addView(intro, marginTop(act, 12));

        TextView label = new TextView(act);
        label.setText("兑换码");
        label.setTextSize(13);
        label.setTextColor(UiStyle.TEXT_SEC);
        card.addView(label, marginTop(act, 20));

        final EditText input = new EditText(act);
        input.setHint("XXXX-XXXX-XXXX-XXXX");
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setTextColor(UiStyle.TEXT_PRI);
        input.setGravity(Gravity.CENTER);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(10);
        bg.setStroke(1, UiStyle.DIVIDER);
        bg.setColor(0xFFF8F9FC);
        input.setBackground(bg);
        input.setPadding(dp(act, 14), dp(act, 14), dp(act, 14), dp(act, 14));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(act, 8);
        card.addView(input, inputLp);

        final TextView error = new TextView(act);
        error.setTextSize(13);
        error.setTextColor(UiStyle.RED);
        error.setGravity(Gravity.CENTER);
        error.setVisibility(View.GONE);
        card.addView(error, marginTop(act, 10));

        final TextView submit = primaryButton(act, "激  活", UiStyle.BLUE, 0xFFFFFFFF);
        card.addView(submit, marginTop(act, 14));

        final ProgressDialog[] holder = new ProgressDialog[1];

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
                        submit.setText("激  活");
                        if (state != null && state.usable()) {
                            Toast.makeText(act, message, Toast.LENGTH_LONG).show();
                            if (onChanged != null) onChanged.onChanged();
                        } else {
                            showError(error, message == null ? "激活失败" : message);
                        }
                    }
                });
            }
        });

        root.addView(card);

        TextView footer = new TextView(act);
        footer.setText("购买 / 续费请联系运营方");
        footer.setTextSize(12);
        footer.setTextColor(UiStyle.TEXT_SEC);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, marginTop(act, 24));

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
        title.setTextColor(UiStyle.TEXT_PRI);
        title.setGravity(Gravity.CENTER);
        card.addView(title, marginTop(act, 12));

        TextView desc = new TextView(act);
        desc.setText(descriptionFor(lm, st));
        desc.setTextSize(14);
        desc.setTextColor(UiStyle.TEXT_SEC);
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(dp(act, 3), 1f);
        card.addView(desc, marginTop(act, 10));

        // 离线超期是可恢复态：给一个显式的「联网重试」
        if (st == LicenseState.OFFLINE_EXPIRED) {
            final TextView retry = primaryButton(act, "联网重试", UiStyle.BLUE, 0xFFFFFFFF);
            card.addView(retry, marginTop(act, 18));
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
                            Toast.makeText(act, message, Toast.LENGTH_LONG).show();
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
            TextView reenter = primaryButton(act, "重新输入兑换码激活",
                    UiStyle.CARD, UiStyle.BLUE);
            GradientDrawable outline = new GradientDrawable();
            outline.setShape(GradientDrawable.RECTANGLE);
            outline.setCornerRadius(dp(act, 10));
            outline.setStroke(dp(act, 1), UiStyle.BLUE);
            outline.setColor(UiStyle.CARD);
            reenter.setBackground(outline);
            card.addView(reenter, marginTop(act, 18));
            reenter.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showActivateDialog(act, lm, onChanged);
                }
            });
        }

        TextView contact = primaryButton(act, "联系客服", UiStyle.BG, UiStyle.TEXT_SEC);
        card.addView(contact, marginTop(act, 10));
        contact.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(act, "请通过购买渠道联系运营方", Toast.LENGTH_LONG).show();
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
            tv.setTextColor(0xFFB26A00);
        }
        bar.addView(tv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        String date = lm.expireDateText();
        if (!date.isEmpty()) {
            TextView sub = new TextView(act);
            sub.setText("至 " + date);
            sub.setTextSize(12);
            sub.setTextColor(UiStyle.TEXT_SEC);
            bar.addView(sub);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(act, 16), dp(act, 10), dp(act, 16), 0);
        bar.setLayoutParams(lp);
        return bar;
    }

    private static int barColor(LicenseState st) {
        if (st == null) return 0xFFF1F3F8;
        switch (st) {
            case ACTIVE:
                return 0xFFE8F0FE;
            case ACTIVE_OFFLINE:
                return 0xFFFFF6E5;
            default:
                return 0xFFFDECEA;
        }
    }

    private static int barTextColor(LicenseState st) {
        if (st == null) return UiStyle.TEXT_SEC;
        switch (st) {
            case ACTIVE:
                return UiStyle.BLUE;
            case ACTIVE_OFFLINE:
                return 0xFF8A5A00;
            default:
                return UiStyle.RED;
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
                            Toast.makeText(act, "兑换码不存在或格式错误",
                                    Toast.LENGTH_SHORT).show();
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
                                Toast.makeText(act, message, Toast.LENGTH_LONG).show();
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
        root.setBackgroundColor(UiStyle.BG);
        root.setPadding(0, 0, 0, dp(act, 28));
        return root;
    }

    private static View wrapScroll(LinearLayout content) {
        ScrollView sv = new ScrollView(content.getContext());
        sv.setBackgroundColor(UiStyle.BG);
        sv.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private static View titleBar(Activity act, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(20);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(UiStyle.BLUE_DARK);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(act, 20), dp(act, 32), dp(act, 8), dp(act, 8));
        return tv;
    }

    private static TextView primaryButton(Activity act, String text, int bgColor, int textColor) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(textColor);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(act, 16), dp(act, 14), dp(act, 16), dp(act, 14));
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(act, 10));
        d.setColor(bgColor);
        tv.setBackground(d);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
