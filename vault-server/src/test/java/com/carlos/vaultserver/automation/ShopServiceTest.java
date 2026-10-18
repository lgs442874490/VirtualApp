package com.carlos.vaultserver.automation;

import com.carlos.vaultserver.automation.services.ShopService;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ShopServiceTest {

    private static ShopService.StoreSnapshot store() {
        ShopService.StoreSnapshot s = new ShopService.StoreSnapshot(1, "普通商店");
        s.goods.add(new ShopService.Good(1001, "免费箱", "free", 0, 1, false, true));
        s.goods.add(new ShopService.Good(1002, "折扣包", "diamond", 50, 2, true, false));
        s.goods.add(new ShopService.Good(1003, "原价包", "diamond", 100, 3, false, false));
        s.goods.add(new ShopService.Good(1004, "售罄", "diamond", 10, 0, true, false));
        return s;
    }

    @Test
    public void selectAllDiscountAndFree() {
        ShopService svc = new ShopService();
        List<ShopService.Good> all = svc.select(store(), Set.of(), true, false, false);
        assertEquals(3, all.size()); // 售罄排除
        List<ShopService.Good> discount = svc.select(store(), Set.of(), false, false, true);
        assertEquals(2, discount.size()); // 免费 + 折扣
        assertTrue(discount.stream().allMatch(g -> g.free || g.discount));
    }

    @Test
    public void refreshPrefersFreeThenDiamondThenStop() {
        ShopService svc = new ShopService();
        ShopService.Policy p = new ShopService.Policy();
        p.targetPurchases = 2;
        p.allowFree = true;
        p.allowDiamond = true;
        p.maxDiamondRefreshes = 1;

        assertEquals(ShopService.RefreshKind.FREE,
                svc.nextRefresh(p, 0, 0, 0).kind);
        assertEquals(ShopService.RefreshKind.DIAMOND,
                svc.nextRefresh(p, 0, 1, 0).kind);
        assertEquals(ShopService.RefreshKind.NONE,
                svc.nextRefresh(p, 0, 1, 1).kind);
        assertEquals(ShopService.RefreshKind.NONE,
                svc.nextRefresh(p, 2, 0, 0).kind);
    }
}
