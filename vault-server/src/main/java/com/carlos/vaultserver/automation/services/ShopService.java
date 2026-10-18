package com.carlos.vaultserver.automation.services;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 商店购买策略（纯逻辑，不直接发协议）。
 *
 * <p>协议侧：req_shop_info_array 拉库存；req_shop_buy 购买；req_shop_refresh 刷新。
 * 本服务负责“买哪些 / 何时刷新 / 刷新几次”的决策，便于单测与配置化。
 */
public final class ShopService {

    public enum RefreshKind { NONE, FREE, DIAMOND }

    public static final class Policy {
        public int targetPurchases = 1;     // 买到 N 件后停止刷新
        public int freeRefreshLimit = 1;    // 免费刷新可用次数
        public boolean allowFree = true;
        public boolean allowDiamond = false;
        public int maxDiamondRefreshes = 0;
        public int maxTotalRefreshes = 20;
    }

    public static final class Decision {
        public final RefreshKind kind;
        public final int diamondUsed;
        public final String reason;

        Decision(RefreshKind kind, int diamondUsed, String reason) {
            this.kind = kind;
            this.diamondUsed = diamondUsed;
            this.reason = reason;
        }
    }

    public static final class Good {
        public long configId;
        public String name = "";
        public String currency = "";   // free / diamond / gold / alliance_coin ...
        public long price;
        public long stock = -1;
        public boolean discount;
        public boolean free;

        public Good(long configId, String name, String currency, long price,
                    long stock, boolean discount, boolean free) {
            this.configId = configId;
            this.name = name;
            this.currency = currency;
            this.price = price;
            this.stock = stock;
            this.discount = discount;
            this.free = free;
        }
    }

    public static final class StoreSnapshot {
        public long shopId;
        public String name = "";
        public final List<Good> goods = new ArrayList<>();
        public int freeRefreshLeft;
        public int diamondRefreshCost;

        public StoreSnapshot(long shopId, String name) {
            this.shopId = shopId;
            this.name = name;
        }
    }

    /** 选中策略：指定 id ∪ 全部 ∪ 免费 ∪ 折扣，且库存>0。 */
    public List<Good> select(StoreSnapshot snapshot, Set<Long> selectedIds,
                             boolean buyAll, boolean buyFree, boolean discountOnly) {
        List<Good> out = new ArrayList<>();
        if (snapshot == null) return out;
        Set<Long> ids = selectedIds == null ? new LinkedHashSet<>() : selectedIds;
        for (Good g : snapshot.goods) {
            if (g.stock == 0) continue;
            boolean hit = buyAll || ids.contains(g.configId)
                    || (buyFree && (g.free || "free".equalsIgnoreCase(g.currency)))
                    || (discountOnly && (g.discount || g.free));
            if (!hit) continue;
            if (discountOnly && !g.discount && !g.free) continue;
            out.add(g);
        }
        return out;
    }

    /**
     * 刷新决策：优先免费；免费耗尽后按配置用钻石；达到购买目标即停。
     *
     * @param purchased 本轮已购买件数
     * @param freeUsed  已用免费刷新次数
     * @param diamondUsed 已用钻石刷新次数
     */
    public Decision nextRefresh(Policy policy, int purchased, int freeUsed, int diamondUsed) {
        Policy p = policy == null ? new Policy() : policy;
        if (purchased >= p.targetPurchases) {
            return new Decision(RefreshKind.NONE, diamondUsed, "已达到购买目标");
        }
        if (p.allowFree && freeUsed < p.freeRefreshLimit) {
            return new Decision(RefreshKind.FREE, diamondUsed, "优先免费刷新");
        }
        if (p.allowDiamond && diamondUsed < p.maxDiamondRefreshes) {
            return new Decision(RefreshKind.DIAMOND, diamondUsed + 1,
                    "免费刷新不可用，按配置钻石刷新");
        }
        return new Decision(RefreshKind.NONE, diamondUsed, "无可用刷新次数");
    }
}
