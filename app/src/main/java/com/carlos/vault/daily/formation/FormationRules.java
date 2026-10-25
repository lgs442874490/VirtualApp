package com.carlos.vault.daily.formation;

import com.carlos.vault.daily.FormationStore;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 编队纯规则函数（契约 §2.4 / 网页规格 §4）：容量公式、比例最大余数分配、容量夹取、校验、比例码、格式化。
 *
 * <p>全部为无 Android 依赖的 static 纯函数，JVM 单测可直跑。
 *
 * <p>文案与网页版逐字一致（契约 §2.5）；占位符用 {@link String#format} 或由调用方拼接。
 */
public final class FormationRules {

    // ==================== 文案（契约 §2.5 逐字） ====================

    public static final String MSG_NAME_REQUIRED = "请填写编队名称";
    /** §2.5 未列该句（R5 要求名称≤30，UI maxlength=30 时不会触发）。 */
    public static final String MSG_NAME_TOO_LONG = "编队名称最多 30 个字";
    public static final String MSG_TOO_MANY_HEROES = "编队最多只能选择三个英雄";
    public static final String MSG_PROFESSION_DUP = "同一编队不能选择两个相同兵种职业的英雄";
    public static final String MSG_CAPTAIN_NOT_IN_HEROES = "队长必须是当前编队已选择的英雄";
    public static final String MSG_RATIO_GT_ZERO = "比例合计必须大于 0%";
    public static final String MSG_RATIO_OVER_100 = "比例合计不能超过 100%";
    public static final String MSG_OVER_CAPACITY = "兵力超过当前档位上限";
    public static final String MSG_ALLOCATION_MODE = "请选择兵量设置方式";
    /** {@code 有 N 名英雄未归队，出征前请确认}（§2.5）。 */
    public static final String MSG_AWAY_FMT = "有 %d 名英雄未归队，出征前请确认";
    public static final String MSG_ALL_GOOD = "本次出征胜券在握";
    public static final String MSG_STATUS_OK = "编队状态正常";
    public static final String MSG_SELECT_ONE = "请选择一支编队";
    /** {@code 请选择 N 支编队}（§2.5）。 */
    public static final String MSG_SELECT_N_FMT = "请选择 %d 支编队";
    /** {@code 还需要选择 N 支编队}（§2.5）。 */
    public static final String MSG_NEED_N_FMT = "还需要选择 %d 支编队";
    public static final String MSG_AUTO_CAPACITY = "出征时按实时最大容量自动分配盾、矛、弓";
    public static final String MSG_SHARED_DENY = "不能与同模块其他队列共用角色编队";
    public static final String MSG_NOT_EXIST = "引用的角色编队不存在，请重新选择";
    /** {@code {英雄所在队列}与{队列}的角色编队存在英雄冲突}（§2.5 / §4.6）。 */
    public static final String MSG_HERO_CONFLICT_FMT = "%s与%s的角色编队存在英雄冲突";
    public static final String MSG_SELECT_FORMATION = "请选择编队";
    public static final String MSG_SAVED_CREATED = "编队已创建";
    public static final String MSG_SAVED_UPDATED = "编队已更新";
    public static final String MSG_SAVED_DELETED = "编队已删除";
    /** {@code 删除“{name}”后，引用它的队列需要重新选择编队。}（§2.5）。 */
    public static final String MSG_DELETE_HINT_FMT = "删除“%s”后，引用它的队列需要重新选择编队。";
    public static final String MSG_OVERWRITE_HINT = "储存后，原先的编队阵容和名称将被覆盖。";
    public static final String MSG_GAME_READONLY = "游戏编队不可直接覆盖，已按副本编辑，保存后生成自定义编队";

    private FormationRules() {
    }

    // ==================== 容量（网页 §4.3 C1/C2） ====================

    /**
     * 出征容量：{@code floor((fixedCapacity + Σ troopCapacity) * (10000 + bp) / 10000)}。
     *
     * @return 未同步（{@code cat.configured()=false}）→ 0；超大数精确计算并夹到 long 上限。
     */
    public static long heroCapacity(FormationCatalogStore cat, List<Long> heroIds) {
        if (cat == null) return 0;
        return cat.capacityFor(heroIds);
    }

    // ==================== 比例 → 兵力（网页 §4.3 C5 / §4.4） ====================

    /**
     * 按比例与容量分配三兵种数量（最大余数法；余数相同按 盾→矛→弓，网页 C5）。
     *
     * <p>先算目标总量 {@code target = round(capacity × ratioSum / 100)}（比例和 1..100），
     * 再按比例权重用最大余数法分配；比例和=0 或容量≤0 → 全 0。
     *
     * @return {@code [盾, 矛, 弓]}
     */
    public static long[] allocateByRatio(long capacity, int infRatio, int lancRatio, int markRatio) {
        long[] out = new long[]{0, 0, 0};
        if (capacity <= 0) return out;
        int[] w = new int[]{Math.max(0, infRatio), Math.max(0, lancRatio), Math.max(0, markRatio)};
        long sum = (long) w[0] + w[1] + w[2];
        if (sum <= 0) return out;

        // target = round(capacity * sum / 100)（四舍五入，避免浮点误差）
        BigInteger cap = BigInteger.valueOf(capacity);
        BigInteger targetB = cap.multiply(BigInteger.valueOf(sum))
                .add(BigInteger.valueOf(50))
                .divide(BigInteger.valueOf(100));
        BigInteger target = capBig(targetB);
        if (target.signum() <= 0) return out;

        BigInteger s = BigInteger.valueOf(sum);
        BigInteger[] base = new BigInteger[3];
        BigInteger[] rem = new BigInteger[3];
        BigInteger assigned = BigInteger.ZERO;
        for (int i = 0; i < 3; i++) {
            BigInteger[] qr = target.multiply(BigInteger.valueOf(w[i])).divideAndRemainder(s);
            base[i] = qr[0];
            rem[i] = qr[1];
            assigned = assigned.add(base[i]);
        }

        BigInteger left = target.subtract(assigned);
        if (left.signum() > 0) {
            Integer[] order = new Integer[]{0, 1, 2};
            final BigInteger[] r = rem;
            java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    int c = r[b].compareTo(r[a]);
                    return c != 0 ? c : a.compareTo(b);
                }
            });
            int k = 0;
            while (left.signum() > 0) {
                int idx = order[k % 3];
                base[idx] = base[idx].add(BigInteger.ONE);
                left = left.subtract(BigInteger.ONE);
                k++;
            }
        }
        for (int i = 0; i < 3; i++) {
            out[i] = FormationCatalogStore.clampLong(base[i]);
        }
        return out;
    }

    /** 比例码（网页 P2）：三项均 10 的倍数 → 各位数字拼接（50/20/30→"523"）；否则 "50:20:30"。 */
    public static String ratioCode(int inf, int lanc, int mark) {
        int a = Math.max(0, inf), b = Math.max(0, lanc), c = Math.max(0, mark);
        if (a % 10 == 0 && b % 10 == 0 && c % 10 == 0) {
            return String.valueOf(a / 10) + (b / 10) + (c / 10);
        }
        return a + ":" + b + ":" + c;
    }

    /** 比例码常量解析（快捷 5 档 523/514/505/640/433）→ {盾,矛,弓}；非法返回 null。 */
    public static int[] parseRatioCode(String code) {
        if (code == null) return null;
        String v = code.trim();
        if (v.length() != 3) return null;
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            char ch = v.charAt(i);
            if (ch < '0' || ch > '9') return null;
            out[i] = (ch - '0') * 10;
        }
        return out;
    }

    // ==================== 容量夹取（网页 §4.3 C6/C7） ====================

    /**
     * 超额按 弓→矛→盾 顺序削减（网页 C7）。
     *
     * <p>容量≤0 视为"未知上限"（未同步时编辑禁用），原样返回，不做夹取。
     *
     * @return {@code [盾, 矛, 弓]}（总和 ≤ capacity）
     */
    public static long[] clampCounts(long capacity, long inf, long lanc, long mark) {
        long[] out = new long[]{Math.max(0, inf), Math.max(0, lanc), Math.max(0, mark)};
        if (capacity <= 0) return out;
        long total = out[0] + out[1] + out[2];
        if (total <= capacity) return out;
        long cut = total - capacity;
        for (int i = 2; i >= 0 && cut > 0; i--) {   // 弓 → 矛 → 盾
            long d = Math.min(out[i], cut);
            out[i] -= d;
            cut -= d;
        }
        return out;
    }

    /** 编队兵力按当前容量夹取（就地修改并返回是否被削减）。 */
    public static boolean clampToCapacity(FormationModel f, long capacity) {
        if (f == null) return false;
        long[] c = clampCounts(capacity, f.infantry, f.lancer, f.marksman);
        boolean changed = c[0] != f.infantry || c[1] != f.lancer || c[2] != f.marksman;
        f.infantry = c[0];
        f.lancer = c[1];
        f.marksman = c[2];
        return changed;
    }

    // ==================== 校验（网页 §4.1 R1/R2/R5 + §4.3 + §4.4 + §4.6） ====================

    /**
     * 编队校验（保存前调用）。
     *
     * @return {@code null}=合法；否则中文原因（契约 §2.5 逐字）。
     */
    public static String validate(FormationModel f, FormationCatalogStore cat) {
        if (f == null) return MSG_SELECT_FORMATION;
        String name = f.name == null ? "" : f.name.trim();
        if (name.isEmpty()) return MSG_NAME_REQUIRED;
        if (name.length() > FormationModel.NAME_MAX) return MSG_NAME_TOO_LONG;

        List<Long> heroes = f.distinctHeroIds();
        if (heroes.size() > FormationModel.HERO_MAX) return MSG_TOO_MANY_HEROES;

        if (f.captainHeroId != null && f.captainHeroId.longValue() > 0
                && !heroes.contains(f.captainHeroId)) {
            return MSG_CAPTAIN_NOT_IN_HEROES;
        }

        String mode = FormationModel.normalizeAllocationMode(f.allocationMode);
        String rawMode = f.allocationMode == null ? "" : f.allocationMode.trim();
        if (rawMode.isEmpty()
                || (!FormationModel.MODE_MANUAL.equalsIgnoreCase(rawMode)
                && !FormationModel.MODE_PERCENTAGE.equalsIgnoreCase(rawMode))) {
            return MSG_ALLOCATION_MODE;
        }

        if (FormationModel.MODE_PERCENTAGE.equals(mode)) {
            int sum = f.ratioSum();
            if (sum <= 0) return MSG_RATIO_GT_ZERO;
            if (sum > 100) return MSG_RATIO_OVER_100;
            if (f.infantryRatio < 0 || f.lancerRatio < 0 || f.marksmanRatio < 0) {
                return MSG_RATIO_GT_ZERO;
            }
        } else if (cat != null && cat.configured()) {
            long cap = heroCapacity(cat, heroes);
            if (cap > 0 && f.soldiersTotal() > cap) return MSG_OVER_CAPACITY;
        }

        // 职业唯一（需要目录英雄职业信息；未知职业不参与判定，避免 415 复发是服务端兜底）
        if (cat != null) {
            Set<Integer> seen = new HashSet<Integer>();
            for (Long id : heroes) {
                int p = professionOf(cat.hero(id.longValue()));
                if (p <= 0) continue;
                if (!seen.add(Integer.valueOf(p))) return MSG_PROFESSION_DUP;
            }
        }
        return null;
    }

    /**
     * 未归队提示（非错误；网页 S4：英雄不在家仍可保存/记忆，只提示）。
     *
     * @return {@code null}=全部在家；否则 {@code 有 N 名英雄未归队，出征前请确认}
     */
    public static String availabilityWarning(FormationModel f, FormationCatalogStore cat) {
        if (f == null || cat == null || cat.heroOptions().isEmpty()) return null;
        Set<Long> away = new HashSet<Long>();
        List<Long> heroes = f.distinctHeroIds();
        for (Long id : heroes) {
            FormationStore.Hero h = cat.hero(id.longValue());
            if (h != null && !h.available) away.add(id);
        }
        if (away.isEmpty()) return null;
        return String.format(Locale.ROOT, MSG_AWAY_FMT, away.size());
    }

    /**
     * 用目录刷新编队可用性字段（allHeroesAvailable/unavailableHeroIds，就地修改）。
     *
     * @return 不在家英雄数（0=全在家）
     */
    public static int markAvailability(FormationModel f, FormationCatalogStore cat) {
        if (f == null) return 0;
        List<Long> away = new ArrayList<Long>();
        if (cat != null && !cat.heroOptions().isEmpty()) {
            for (Long id : f.distinctHeroIds()) {
                FormationStore.Hero h = cat.hero(id.longValue());
                if (h != null && !h.available) away.add(id);
            }
        }
        f.unavailableHeroIds.clear();
        f.unavailableHeroIds.addAll(away);
        f.allHeroesAvailable = away.isEmpty();
        return away.size();
    }

    // ==================== 跨队列冲突（网页 §4.2 S3 / §4.6） ====================

    /**
     * 英雄冲突检测：候选编队与已绑定编队是否有相同英雄。
     *
     * @param lib        编队库（含候选）
     * @param bindingIds 当前功能已绑定的编队 id（可含候选自身）
     * @param candidateId 待加入的编队 id
     * @return {@code null}=无冲突；否则 {@code "{候选队名}与{冲突队名}的角色编队存在英雄冲突"}
     */
    public static String heroConflict(List<FormationModel> lib, long[] bindingIds, long candidateId) {
        FormationModel candidate = findById(lib, candidateId);
        if (candidate == null) return null;
        List<Long> heroIds = candidate.distinctHeroIds();
        if (heroIds.isEmpty()) return null;
        if (bindingIds != null) {
            for (long otherId : bindingIds) {
                if (otherId == candidateId) continue;
                FormationModel other = findById(lib, otherId);
                if (other == null) continue;
                long dup = firstSharedHero(heroIds, other.distinctHeroIds());
                if (dup > 0) {
                    return String.format(Locale.ROOT, MSG_HERO_CONFLICT_FMT,
                            quote(candidate.name), quote(other.name));
                }
            }
        }
        return null;
    }

    /** 英雄冲突的具体英雄 id（无冲突返回 -1；便于 UI 显示英雄名）。 */
    public static long heroConflictHeroId(List<FormationModel> lib, long[] bindingIds,
                                          long candidateId) {
        FormationModel candidate = findById(lib, candidateId);
        if (candidate == null) return -1;
        List<Long> heroIds = candidate.distinctHeroIds();
        if (bindingIds != null) {
            for (long otherId : bindingIds) {
                if (otherId == candidateId) continue;
                FormationModel other = findById(lib, otherId);
                if (other == null) continue;
                long dup = firstSharedHero(heroIds, other.distinctHeroIds());
                if (dup > 0) return dup;
            }
        }
        return -1;
    }

    /** 职业：1 盾 / 2 矛 / 3 弓 / 0 未知（优先 professionType，其次中文 profession）。 */
    public static int professionOf(FormationStore.Hero h) {
        if (h == null) return 0;
        if (h.professionType >= 1 && h.professionType <= 3) return h.professionType;
        return professionTypeOf(h.profession);
    }

    /** 中文职业名 → 职业类型（1 盾/2 矛/3 弓/0 未知）。 */
    public static int professionTypeOf(String name) {
        if (name == null) return 0;
        String v = name.trim();
        if (v.isEmpty()) return 0;
        if (v.startsWith("盾")) return 1;
        if (v.startsWith("矛") || v.startsWith("枪") || v.startsWith("长枪")) return 2;
        if (v.startsWith("弓") || v.startsWith("射")) return 3;
        return 0;
    }

    /** 职业中文名（1 盾兵 / 2 矛兵 / 3 射手 / 0 空串）。 */
    public static String professionName(int professionType) {
        switch (professionType) {
            case 1:
                return "盾兵";
            case 2:
                return "矛兵";
            case 3:
                return "射手";
            default:
                return "";
        }
    }

    /** 千分位格式化：181400 → {@code 181,400}。 */
    public static String fmt(long v) {
        BigInteger n = BigInteger.valueOf(v).abs();
        StringBuilder sb = new StringBuilder(n.toString());
        for (int i = sb.length() - 3; i > 0; i -= 3) {
            sb.insert(i, ',');
        }
        return (v < 0 ? "-" : "") + sb;
    }

    /** 按 id 找编队；找不到返回 null。 */
    public static FormationModel findById(List<FormationModel> lib, long id) {
        if (lib == null) return null;
        for (FormationModel f : lib) {
            if (f != null && f.id == id) return f;
        }
        return null;
    }

    /** 比例和是否合法（1..100，网页 P1）。 */
    public static boolean ratioInRange(int inf, int lanc, int mark) {
        int sum = Math.max(0, inf) + Math.max(0, lanc) + Math.max(0, mark);
        return sum > 0 && sum <= 100 && inf >= 0 && lanc >= 0 && mark >= 0
                && inf <= 100 && lanc <= 100 && mark <= 100;
    }

    // ==================== 内部 ====================

    private static BigInteger capBig(BigInteger v) {
        if (v.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            return BigInteger.valueOf(Long.MAX_VALUE);
        }
        return v;
    }

    private static long firstSharedHero(List<Long> a, List<Long> b) {
        for (Long h : a) {
            if (h == null) continue;
            for (Long o : b) {
                if (o != null && o.longValue() == h.longValue()) return h.longValue();
            }
        }
        return -1;
    }

    private static String quote(String name) {
        String n = name == null || name.trim().isEmpty() ? "编队" : name.trim();
        return "“" + n + "”";
    }
}
