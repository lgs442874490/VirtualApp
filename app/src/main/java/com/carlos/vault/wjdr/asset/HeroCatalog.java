package com.carlos.vault.wjdr.asset;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 英雄基础目录（职业/名称），1:1 移植网页版 {@code HeroProfessionCatalog}。
 * App/Server 同源；纯 java.util，Java 8 兼容。
 */
public final class HeroCatalog {

    public static final int INFANTRY = 1;
    public static final int LANCER = 2;
    public static final int MARKSMAN = 3;

    private static final Map<Long, Integer> TYPES = new LinkedHashMap<Long, Integer>();
    private static final Map<Long, String> NAMES = new LinkedHashMap<Long, String>();

    static {
        TYPES.put(50000L, 1);
        TYPES.put(50001L, 1);
        TYPES.put(50002L, 2);
        TYPES.put(50003L, 3);
        TYPES.put(50004L, 1);
        TYPES.put(50005L, 2);
        TYPES.put(50006L, 3);
        TYPES.put(50007L, 2);
        TYPES.put(50008L, 3);
        TYPES.put(50009L, 1);
        TYPES.put(50010L, 1);
        TYPES.put(50011L, 1);
        TYPES.put(50012L, 2);
        TYPES.put(50013L, 2);
        TYPES.put(50014L, 2);
        TYPES.put(50015L, 2);
        TYPES.put(50016L, 2);
        TYPES.put(50017L, 3);
        TYPES.put(50018L, 3);
        TYPES.put(50019L, 1);
        TYPES.put(50020L, 3);
        TYPES.put(50021L, 1);
        TYPES.put(50022L, 3);
        TYPES.put(50023L, 1);
        TYPES.put(50024L, 1);
        TYPES.put(50025L, 2);
        TYPES.put(50026L, 3);
        TYPES.put(50027L, 3);
        TYPES.put(50028L, 3);
        TYPES.put(50029L, 2);
        TYPES.put(50030L, 2);
        TYPES.put(50031L, 3);
        TYPES.put(50032L, 1);
        TYPES.put(50033L, 3);
        TYPES.put(50034L, 1);
        TYPES.put(50035L, 2);
        TYPES.put(50036L, 3);
        TYPES.put(50037L, 2);
        TYPES.put(50038L, 1);
        TYPES.put(50039L, 2);
        TYPES.put(50040L, 3);
        TYPES.put(50041L, 1);
        TYPES.put(50042L, 2);
        TYPES.put(50043L, 3);
        TYPES.put(50044L, 1);
        TYPES.put(50045L, 2);
        TYPES.put(50046L, 3);
        TYPES.put(50047L, 1);
        TYPES.put(50048L, 2);
        TYPES.put(50049L, 3);
        TYPES.put(50050L, 1);
        TYPES.put(50051L, 2);
        TYPES.put(50052L, 3);
        TYPES.put(50053L, 1);
        TYPES.put(50054L, 2);
        TYPES.put(50055L, 3);
        TYPES.put(50056L, 1);
        TYPES.put(50057L, 2);
        TYPES.put(50058L, 3);
        TYPES.put(50059L, 1);
        TYPES.put(50060L, 2);
        TYPES.put(50061L, 3);

        NAMES.put(50000L, "\"尤金\"");
        NAMES.put(50001L, "\"史密斯\"");
        NAMES.put(50002L, "\"查理\"");
        NAMES.put(50003L, "\"克劳瑞斯\"");
        NAMES.put(50004L, "\"谢尔盖\"");
        NAMES.put(50005L, "\"帕特里克\"");
        NAMES.put(50006L, "\"巴希提\"");
        NAMES.put(50007L, "\"杰西\"");
        NAMES.put(50008L, "\"吉娜\"");
        NAMES.put(50009L, "\"娜塔莉亚\"");
        NAMES.put(50010L, "\"罗根\"");
        NAMES.put(50011L, "\"赫罗尼莫\"");
        NAMES.put(50012L, "\"茉莉\"");
        NAMES.put(50013L, "\"菲兰德\"");
        NAMES.put(50014L, "\"玲奈\"");
        NAMES.put(50015L, "\"哥顿\"");
        NAMES.put(50016L, "\"米娅\"");
        NAMES.put(50017L, "\"津曼\"");
        NAMES.put(50018L, "\"阿隆索\"");
        NAMES.put(50019L, "\"无名\"");
        NAMES.put(50020L, "\"格雷格\"");
        NAMES.put(50021L, "\"弗林特\"");
        NAMES.put(50022L, "\"琳恩\"");
        NAMES.put(50023L, "\"阿赫摩斯\"");
        NAMES.put(50024L, "\"赫克托\"");
        NAMES.put(50025L, "\"诺拉\"");
        NAMES.put(50026L, "\"格温\"");
        NAMES.put(50027L, "\"书允\"");
        NAMES.put(50028L, "\"杰塞尔\"");
        NAMES.put(50029L, "\"卢姆·波根\"");
        NAMES.put(50030L, "\"芮妮\"");
        NAMES.put(50031L, "\"韦恩\"");
        NAMES.put(50032L, "\"艾迪丝\"");
        NAMES.put(50033L, "\"布拉德利\"");
        NAMES.put(50034L, "\"加托\"");
        NAMES.put(50035L, "\"索尼娅\"");
        NAMES.put(50036L, "\"亨德里克\"");
        NAMES.put(50037L, "\"凌霜\"");
        NAMES.put(50038L, "\"马格努斯\"");
        NAMES.put(50039L, "\"弗雷德\"");
        NAMES.put(50040L, "\"修拉\"");
        NAMES.put(50041L, "\"格里高利\"");
        NAMES.put(50042L, "\"芙蕾雅\"");
        NAMES.put(50043L, "\"布兰琪\"");
        NAMES.put(50044L, "\"埃莱奥诺拉\"");
        NAMES.put(50045L, "\"劳埃德\"");
        NAMES.put(50046L, "\"鲁弗斯\"");
        NAMES.put(50047L, "\"赫尔薇尔\"");
        NAMES.put(50048L, "\"加罗尔\"");
        NAMES.put(50049L, "\"丽姬娅\"");
        NAMES.put(50050L, "\"吉塞拉\"");
        NAMES.put(50051L, "\"弗洛拉\"");
        NAMES.put(50052L, "\"乌尔卡努斯\"");
        NAMES.put(50053L, "\"艾丽芙\"");
        NAMES.put(50054L, "\"多米尼克\"");
        NAMES.put(50055L, "\"卡拉\"");
        NAMES.put(50056L, "\"汉克\"");
        NAMES.put(50057L, "\"艾丝黛拉\"");
        NAMES.put(50058L, "\"维薇卡\"");
        NAMES.put(50059L, "\"西格尔\"");
        NAMES.put(50060L, "\"乌尔撒\"");
        NAMES.put(50061L, "\"艾诗琳\"");
    }

    /** 职业：1=盾兵、2=矛兵、3=射手；未知 0。 */
    public static int profession(long heroId) {
        Integer v = TYPES.get(heroId);
        return v == null ? 0 : v.intValue();
    }

    /** 名称（未知返回空串，UI 可回退显示 id）。 */
    public static String name(long heroId) {
        String v = NAMES.get(heroId);
        return v == null ? "" : v;
    }

    /** 职业中文名。 */
    public static String professionName(long heroId) {
        switch (profession(heroId)) {
            case INFANTRY: return "盾兵";
            case LANCER: return "矛兵";
            case MARKSMAN: return "射手";
            default: return "未知职业";
        }
    }

    public static Map<Long, Integer> types() {
        return Collections.unmodifiableMap(TYPES);
    }

    private HeroCatalog() {
    }
}
