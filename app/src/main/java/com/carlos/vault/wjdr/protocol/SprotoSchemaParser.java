package com.carlos.vault.wjdr.protocol;

import com.carlos.vault.wjdr.protocol.SprotoBinarySupport;
import com.carlos.vault.wjdr.protocol.SprotoFieldDef;
import com.carlos.vault.wjdr.protocol.SprotoProtocolDef;
import com.carlos.vault.wjdr.protocol.SprotoSchema;
import com.carlos.vault.wjdr.protocol.SprotoTypeDef;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class SprotoSchemaParser {
    public SprotoSchema parse(byte[] bundle) {
        int i;
        SprotoSchema schema = new SprotoSchema();
        int rootFieldCount = this.structFieldCount(bundle, bundle.length);
        if (rootFieldCount < 1 || rootFieldCount > 2) {
            throw new IllegalArgumentException("Invalid sproto bundle");
        }
        int headerOffset = 2;
        int contentOffset = 2 + rootFieldCount * 2;
        ArrayList rootArrays = new ArrayList();
        for (int i2 = 0; i2 < rootFieldCount; ++i2) {
            int value = SprotoSchemaParser.word(bundle, headerOffset + i2 * 2);
            if (value != 0) {
                throw new IllegalArgumentException("Invalid root bundle field");
            }
            int contentSize = SprotoBinarySupport.readInt32(bundle, contentOffset);
            rootArrays.add(SprotoSchemaParser.slice(bundle, contentOffset + 4, contentSize));
            contentOffset += 4 + contentSize;
        }
        if (rootArrays.isEmpty()) {
            return schema;
        }
        List<byte[]> typesRaw = this.splitArrayObjects((byte[])rootArrays.get(0));
        for (i = 0; i < typesRaw.size(); ++i) {
            schema.addType(new SprotoTypeDef());
        }
        for (i = 0; i < typesRaw.size(); ++i) {
            this.parseType(typesRaw.get(i), schema.getTypes().get(i), schema);
        }
        if (rootArrays.size() > 1) {
            List<byte[]> protocolsRaw = this.splitArrayObjects((byte[])rootArrays.get(1));
            for (byte[] protocolRaw : protocolsRaw) {
                schema.addProtocol(this.parseProtocol(protocolRaw, schema));
            }
        }
        return schema;
    }

    private void parseType(byte[] raw, SprotoTypeDef type, SprotoSchema schema) {
        int fn = this.structFieldCount(raw, raw.length);
        if (fn <= 0 || fn > 2) {
            throw new IllegalArgumentException("Invalid sproto type block");
        }
        int dataOffset = 2 + fn * 2;
        type.setName(this.readString(raw, dataOffset));
        type.getFields().clear();
        if (fn == 1) {
            type.setBase(-1);
            type.setMaxFields(0);
            schema.getTypesByName().put(type.getName(), type);
            return;
        }
        dataOffset += 4 + SprotoBinarySupport.readInt32(raw, dataOffset);
        byte[] fieldsArray = SprotoSchemaParser.slice(raw, dataOffset + 4, SprotoBinarySupport.readInt32(raw, dataOffset));
        List<byte[]> fieldBlocks = this.splitArrayObjects(fieldsArray);
        int maxFields = fieldBlocks.size();
        int lastTag = -1;
        for (byte[] fieldRaw : fieldBlocks) {
            SprotoFieldDef field = this.parseField(fieldRaw, schema);
            if (field.getTag() <= lastTag) {
                throw new IllegalArgumentException("Field tags must be strictly ascending");
            }
            if (field.getTag() > lastTag + 1) {
                ++maxFields;
            }
            type.getFields().add(field);
            lastTag = field.getTag();
        }
        type.setMaxFields(maxFields);
        if (!type.getFields().isEmpty()) {
            type.setBase(type.getFields().get(0).getTag());
            int span = type.getFields().get(type.getFields().size() - 1).getTag() - type.getBase() + 1;
            if (span != type.getFields().size()) {
                type.setBase(-1);
            }
        }
        schema.getTypesByName().put(type.getName(), type);
    }

    private SprotoFieldDef parseField(byte[] raw, SprotoSchema schema) {
        int fn = this.structFieldCount(raw, raw.length);
        if (fn < 0) {
            throw new IllegalArgumentException("Invalid sproto field block");
        }
        int streamOffset = 2;
        int dataOffset = 2 + fn * 2;
        int tag = -1;
        boolean array = false;
        SprotoFieldDef field = new SprotoFieldDef();
        field.setTag(-1);
        field.setType(-1);
        block8: for (int i = 0; i < fn; ++i) {
            ++tag;
            int value = SprotoSchemaParser.word(raw, streamOffset + i * 2);
            if ((value & 1) == 1) {
                tag += value / 2;
                continue;
            }
            if (tag == 0) {
                if (value != 0) {
                    throw new IllegalArgumentException("Invalid field name value");
                }
                field.setName(this.readString(raw, dataOffset));
                continue;
            }
            if (value == 0) {
                throw new IllegalArgumentException("Unexpected zero field value");
            }
            int actual = value / 2 - 1;
            switch (tag) {
                case 1: {
                    if (actual >= 4) {
                        throw new IllegalArgumentException("Invalid builtin type");
                    }
                    field.setType(actual);
                    continue block8;
                }
                case 2: {
                    if (field.getType() == 0) {
                        field.setExtra(actual > 0 ? SprotoSchemaParser.calcPow(10, actual) : 0);
                        continue block8;
                    }
                    if (field.getType() == 2) {
                        field.setExtra(actual);
                        continue block8;
                    }
                    if (actual < 0 || actual >= schema.getTypes().size()) {
                        throw new IllegalArgumentException("Invalid subtype index");
                    }
                    field.setType(4);
                    field.setSubtype(schema.getTypes().get(actual));
                    continue block8;
                }
                case 3: {
                    field.setTag(actual);
                    continue block8;
                }
                case 4: {
                    array = actual != 0;
                    continue block8;
                }
                case 5: {
                    field.setKey(actual);
                    continue block8;
                }
                case 6: {
                    if (actual == 0) continue block8;
                    field.setMap(1);
                    continue block8;
                }
                default: {
                    throw new IllegalArgumentException("Unsupported field attribute tag: " + tag);
                }
            }
        }
        if (field.getTag() < 0 || field.getType() < 0 || field.getName() == null) {
            throw new IllegalArgumentException("Incomplete field definition");
        }
        if (array) {
            field.setType(field.getType() | 0x80);
        }
        return field;
    }

    private SprotoProtocolDef parseProtocol(byte[] raw, SprotoSchema schema) {
        int fn = this.structFieldCount(raw, raw.length);
        int streamOffset = 2;
        int dataOffset = 2 + fn * 2;
        SprotoProtocolDef protocol = new SprotoProtocolDef();
        int tag = 0;
        int i = 0;
        while (i < fn) {
            int value = SprotoSchemaParser.word(raw, streamOffset + i * 2);
            if ((value & 1) == 1) {
                tag += (value - 1) / 2;
            } else {
                int actual = value / 2 - 1;
                switch (i) {
                    case 0: {
                        if (actual != -1) {
                            throw new IllegalArgumentException("Invalid protocol name field");
                        }
                        protocol.setName(this.readString(raw, dataOffset));
                        break;
                    }
                    case 1: {
                        protocol.setTag(actual);
                        break;
                    }
                    case 2: {
                        if (actual < 0) break;
                        protocol.setRequestType(schema.getTypes().get(actual));
                        break;
                    }
                    case 3: {
                        if (actual < 0) break;
                        protocol.setResponseType(schema.getTypes().get(actual));
                        break;
                    }
                    case 4: {
                        protocol.setConfirm(actual);
                        break;
                    }
                    default: {
                        throw new IllegalArgumentException("Unsupported protocol attribute");
                    }
                }
            }
            ++i;
            ++tag;
        }
        if (protocol.getName() == null || protocol.getTag() < 0) {
            throw new IllegalArgumentException("Incomplete protocol definition");
        }
        return protocol;
    }

    private List<byte[]> splitArrayObjects(byte[] arrayContent) {
        // ⚠️ CFR 把字节码求值顺序渲染反了（先读 size 再 offset+=4），这里按真实字节码修正
        ArrayList<byte[]> blocks = new ArrayList<byte[]>();
        int offset = 0;
        while (offset < arrayContent.length) {
            if (offset + 4 > arrayContent.length) {
                throw new IllegalArgumentException("Invalid sproto array length");
            }
            int size = SprotoBinarySupport.readInt32(arrayContent, offset);
            offset += 4;
            if (offset + size > arrayContent.length) {
                throw new IllegalArgumentException("Invalid sproto array object");
            }
            blocks.add(SprotoSchemaParser.slice(arrayContent, offset, size));
            offset += size;
        }
        return blocks;
    }

    private int structFieldCount(byte[] bytes, int size) {
        if (size < 4) {
            return -1;
        }
        int fn = SprotoSchemaParser.word(bytes, 0);
        int headerSize = 2 + fn * 2;
        if (size < headerSize) {
            return -1;
        }
        int remain = size - headerSize;
        int dataOffset = headerSize;
        for (int i = 0; i < fn; ++i) {
            int value = SprotoSchemaParser.word(bytes, 2 + i * 2);
            if (value != 0) continue;
            if (remain < 4) {
                return -1;
            }
            int blockSize = SprotoBinarySupport.readInt32(bytes, dataOffset);
            if (remain < 4 + blockSize) {
                return -1;
            }
            dataOffset += 4 + blockSize;
            remain -= 4 + blockSize;
        }
        return fn;
    }

    private String readString(byte[] bytes, int offset) {
        int size = SprotoBinarySupport.readInt32(bytes, offset);
        return new String(bytes, offset + 4, size, StandardCharsets.UTF_8);
    }

    private static int calcPow(int base, int n) {
        if (n == 0) {
            return 1;
        }
        int result = SprotoSchemaParser.calcPow(base * base, n / 2);
        if ((n & 1) == 1) {
            result *= base;
        }
        return result;
    }

    private static int word(byte[] data, int offset) {
        return data[offset] & 0xFF | (data[offset + 1] & 0xFF) << 8;
    }

    private static byte[] slice(byte[] data, int offset, int length) {
        byte[] result = new byte[length];
        System.arraycopy(data, offset, result, 0, length);
        return result;
    }
}

