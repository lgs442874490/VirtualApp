package com.carlos.vaultserver.automation;

import com.carlos.vaultserver.automation.services.FormationPlanner;
import com.carlos.vaultserver.automation.services.HeroDirectory;
import com.carlos.vaultserver.automation.services.MedicService;
import com.carlos.vaultserver.automation.services.ShopService;

/**
 * 跨模块共享服务。模块只依赖这些稳定接口，不互相依赖。
 * 新增领域能力（如银行周期计算、奖励目录）优先落到独立 Service。
 */
public final class AutomationServices {

    public final FormationPlanner formations;
    public final HeroDirectory heroes;
    public final MedicService medic;
    public final ShopService shops;

    public AutomationServices(FormationPlanner formations, HeroDirectory heroes,
                              MedicService medic, ShopService shops) {
        this.formations = formations;
        this.heroes = heroes;
        this.medic = medic;
        this.shops = shops;
    }

    public static AutomationServices defaults() {
        FormationPlanner planner = new com.carlos.vaultserver.automation.services
                .DefaultFormationPlanner();
        return new AutomationServices(planner, HeroDirectory.empty(),
                new MedicService(), new ShopService());
    }
}
