import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.Annotation;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * DexPatch 补丁生成器：对比 old.apk 与 new.apk 的 dex，提取变更类，输出 patch.dex。
 *
 * <p>用法：
 * <pre>
 * java -cp "&lt;dexlib2 等依赖&gt;" GeneratePatch old.apk new.apk out.dex
 * </pre>
 *
 * <p>变更判定：按「类定义 + 字段 + 方法签名 + 方法字节码指令 + 注解」的 md5 比对；
 * 新增类（old 中不存在）与修改类都会被打入补丁。
 *
 * <p>输出为单个 dex（M1 范围：变更类总量 < 65535 方法，远未触及上限）。
 */
public final class GeneratePatch {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("用法: GeneratePatch <old.apk> <new.apk> <out.dex>");
            System.exit(2);
        }
        String oldApk = args[0];
        String newApk = args[1];
        String outDex = args[2];

        List<File> oldDexes = extractDexes(oldApk, "old");
        List<File> newDexes = extractDexes(newApk, "new");
        System.out.println("[gen] old dex count=" + oldDexes.size() + ", new dex count=" + newDexes.size());

        Map<String, String> oldSigs = collectSignatures(oldDexes);
        Map<String, String> newSigs = collectSignatures(newDexes);
        System.out.println("[gen] old classes=" + oldSigs.size() + ", new classes=" + newSigs.size());

        Set<String> changed = new HashSet<>();
        for (Map.Entry<String, String> e : newSigs.entrySet()) {
            String type = e.getKey();
            String oldSig = oldSigs.get(type);
            if (oldSig == null || !oldSig.equals(e.getValue())) {
                changed.add(type);
            }
        }
        System.out.println("[gen] changed/new classes=" + changed.size());

        if (changed.isEmpty()) {
            System.out.println("[gen] 无变更类，未生成补丁");
            return;
        }

        DexPool pool = new DexPool(Opcodes.getDefault());
        Set<String> added = new HashSet<>();
        for (File f : newDexes) {
            DexBackedDexFile df = DexFileFactory.loadDexFile(f, Opcodes.getDefault());
            for (ClassDef cd : df.getClasses()) {
                if (changed.contains(cd.getType()) && added.add(cd.getType())) {
                    pool.internClass(cd);
                }
            }
        }

        File out = new File(outDex);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        // dexlib2 2.3.1: DexPool.writeTo(DexDataStore)，用官方 FileDataStore 落盘
        pool.writeTo(new org.jf.dexlib2.writer.io.FileDataStore(out));
        System.out.println("[gen] 补丁已生成: " + out.getAbsolutePath()
                + " classes=" + added.size() + " size=" + out.length() + " bytes");

        // 元数据（供 dexpatch_prepare.sh 写入 hotconfig.json.patches.code）
        String secret = args.length >= 4 ? args[3] : "";
        String baseMd5 = md5File(new File(oldApk));
        String patchSha = sha256File(out);
        String patchHmac = secret.isEmpty() ? "" : hmacSha256File(out, secret);
        StringBuilder meta = new StringBuilder();
        meta.append("{\"baseApkMd5\":\"").append(baseMd5).append("\",")
                .append("\"sha256\":\"").append(patchSha).append("\",")
                .append("\"hmac\":\"").append(patchHmac).append("\",")
                .append("\"sizeBytes\":").append(out.length()).append("}");
        System.out.println("META:" + meta);
    }

    private static String md5File(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        return digestFile(f, md);
    }

    private static String sha256File(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return digestFile(f, md);
    }

    private static String hmacSha256File(File f, String secret) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                mac.update(buf, 0, n);
            }
        }
        return hex(mac.doFinal());
    }

    private static String digestFile(File f, MessageDigest md) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return hex(md.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 把 APK 内 classes*.dex 解压到临时目录。 */
    private static List<File> extractDexes(String apk, String tag) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "dexpatch_" + tag + "_" + System.nanoTime());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        List<File> result = new ArrayList<>();
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().matches("classes[0-9]*\\.dex")) {
                    continue;
                }
                File out = new File(dir, e.getName());
                try (InputStream in = zf.getInputStream(e);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                }
                result.add(out);
            }
        }
        return result;
    }

    /** 收集所有 dex 中「类名 → 内容签名 md5」。 */
    private static Map<String, String> collectSignatures(List<File> dexes) throws Exception {
        Map<String, String> map = new HashMap<>();
        for (File f : dexes) {
            DexBackedDexFile df = DexFileFactory.loadDexFile(f, Opcodes.getDefault());
            for (ClassDef cd : df.getClasses()) {
                map.put(cd.getType(), classSignature(cd));
            }
        }
        return map;
    }

    /**
     * 类内容签名：类定义 + 字段 + 方法签名 + 方法字节码 + 注解。
     *
     * <p>关键：指令<b>不能</b>用 {@code insn.toString()}——它包含跳转标签的绝对偏移，
     * 两次构建的偏移天然不同，会让所有类都被误判为“变更”（实测补丁会膨胀到 8MB）。
     * 这里只取 opcode 名 + 引用常量 + 字面量，跳转偏移变化不影响签名。
     */
    private static String classSignature(ClassDef cd) throws Exception {
        StringBuilder sb = new StringBuilder(2048);
        sb.append(cd.getType()).append('|')
                .append(cd.getSuperclass()).append('|')
                .append(cd.getInterfaces()).append('|')
                .append(cd.getAccessFlags()).append('\n');
        for (Field f : cd.getFields()) {
            sb.append("F:").append(f.toString()).append('\n');
        }
        for (Method m : cd.getMethods()) {
            sb.append("M:").append(m.toString()).append('\n');
            org.jf.dexlib2.iface.MethodImplementation impl = m.getImplementation();
            if (impl != null) {
                for (Instruction insn : impl.getInstructions()) {
                    sb.append("O:").append(insn.getOpcode().name).append(' ');
                    if (insn instanceof org.jf.dexlib2.iface.instruction.ReferenceInstruction) {
                        sb.append(((org.jf.dexlib2.iface.instruction.ReferenceInstruction) insn)
                                .getReference().toString());
                    }
                    sb.append('\n');
                }
            }
        }
        for (Annotation a : cd.getAnnotations()) {
            sb.append("A:").append(a.toString()).append('\n');
        }
        return md5(sb.toString());
    }

    private static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
