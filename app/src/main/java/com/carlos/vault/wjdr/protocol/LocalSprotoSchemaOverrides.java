package com.carlos.vault.wjdr.protocol;

import com.carlos.vault.wjdr.protocol.SprotoFieldDef;
import com.carlos.vault.wjdr.protocol.SprotoSchema;
import com.carlos.vault.wjdr.protocol.SprotoTypeDef;
import java.util.Comparator;

final class LocalSprotoSchemaOverrides {
    private LocalSprotoSchemaOverrides() {
    }

    static void applyC2s(SprotoSchema schema) {
        SprotoTypeDef useItemExtra = schema.getTypesByName().get("use_item_extra");
        if (useItemExtra == null) {
            return;
        }
        LocalSprotoSchemaOverrides.addIntegerField(useItemExtra, 3, "x");
        LocalSprotoSchemaOverrides.addIntegerField(useItemExtra, 4, "y");
        LocalSprotoSchemaOverrides.addIntegerField(useItemExtra, 5, "mapType");
    }

    private static void addIntegerField(SprotoTypeDef type, int tag, String name) {
        if (type.findField(tag) != null) {
            return;
        }
        SprotoFieldDef field = new SprotoFieldDef();
        field.setName(name);
        field.setTag(tag);
        field.setType(0);
        type.getFields().add(field);
        type.getFields().sort(Comparator.comparingInt(SprotoFieldDef::getTag));
        type.setBase(-1);
        type.setMaxFields(Math.max(type.getMaxFields(), tag + 1));
    }
}

