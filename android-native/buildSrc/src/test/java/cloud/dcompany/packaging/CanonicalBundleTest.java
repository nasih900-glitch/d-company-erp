package cloud.dcompany.packaging;

import com.android.tools.profgen.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CanonicalBundleTest {
    private static byte[] dex(int label) throws Exception {
        byte[] bytes = new byte[112 + label]; ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        System.arraycopy(new byte[]{'d','e','x','\n','0','3','9',0}, 0, bytes, 0, 8);
        b.putInt(32, bytes.length); b.putInt(36, 112); b.putInt(40, 0x12345678);
        Arrays.fill(bytes, 112, bytes.length, (byte)label);
        System.arraycopy(MessageDigest.getInstance("SHA-1").digest(Arrays.copyOfRange(bytes, 32, bytes.length)), 0, bytes, 12, 20);
        Adler32 adler = new Adler32(); adler.update(bytes, 12, bytes.length - 12); b.putInt(8, (int)adler.getValue()); return bytes;
    }
    private static String name(int n) { return n == 1 ? "classes.dex" : "classes" + n + ".dex"; }
    private static byte[] profile(Map<String, byte[]> dex, boolean metadata) throws Exception {
        Map<DexFile, DexFileData> data = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : dex.entrySet()) data.put(DexFileParserKt.parseDexFile(e.getValue(), e.getKey()), new DexFileData(Set.of(), Set.of(), Map.of()));
        ByteArrayOutputStream out = new ByteArrayOutputStream(); new ArtProfile(data, "").save(out,
                metadata ? ArtProfileSerializer.METADATA_0_0_2 : ArtProfileSerializer.V0_1_0_P); return out.toByteArray();
    }
    private static void zip(Path path, Map<String, byte[]> entries) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) { ZipEntry z = new ZipEntry(e.getKey()); z.setTime(315532800000L); out.putNextEntry(z); out.write(e.getValue()); out.closeEntry(); }
        }
    }
    private record Fixture(Path apk, Path aab, Map<String, byte[]> dex, Map<String, byte[]> entries) {}
    private static Fixture fixture(Path root, int... order) throws Exception {
        Files.createDirectories(root); Map<String, byte[]> dex = new LinkedHashMap<>(); for (int i = 1; i <= 4; i++) dex.put(name(i), dex(i));
        byte[] prof = profile(dex, false), meta = profile(dex, true); Map<String, byte[]> apk = new LinkedHashMap<>(dex);
        apk.put("assets/dexopt/baseline.prof", prof); apk.put("assets/dexopt/baseline.profm", meta); zip(root.resolve("play.apk"), apk);
        Map<String, byte[]> ab = new LinkedHashMap<>(); for (int i = 0; i < order.length; i++) ab.put("base/dex/" + name(i + 1), dex.get(name(order[i])));
        ab.put("BundleConfig.pb", new byte[]{1,2}); ab.put("base/manifest/AndroidManifest.xml", new byte[]{3}); ab.put("base/assets/preserve", new byte[]{4,5,6});
        ab.put("META-INF/com/android/build/gradle/app-metadata.properties", new byte[]{7});
        ab.put("BUNDLE-METADATA/com.android.tools.build.profiles/baseline.prof", prof); ab.put("BUNDLE-METADATA/com.android.tools.build.profiles/baseline.profm", meta);
        zip(root.resolve("raw.aab"), ab); return new Fixture(root.resolve("play.apk"), root.resolve("raw.aab"), dex, ab);
    }
    private static Path run(Fixture f, Path root, String suffix) throws Exception {
        Path out = root.resolve(suffix + ".aab"); CanonicalBundle.write(f.aab, f.apk, out, root.resolve(suffix + "-audit")); return out;
    }
    private static void reject(Fixture f, Path root) throws Exception {
        String before = CanonicalBundle.hash(f.aab), apkBefore = CanonicalBundle.hash(f.apk);
        Path out = root.resolve("rejected.aab"); assertThrows(Exception.class, () -> CanonicalBundle.write(f.aab, f.apk, out, root.resolve("reject-audit"))); assertFalse(Files.exists(out));
        assertEquals(before, CanonicalBundle.hash(f.aab)); assertEquals(apkBefore, CanonicalBundle.hash(f.apk));
    }
    @Test public void allSixSecondaryPermutationsConvergeWithoutRecompressingAndAreIdempotent() throws Exception {
        Path root = Files.createTempDirectory("bundle-permutations"); Set<String> results = new HashSet<>();
        int[][] orders = {{1,2,3,4},{1,2,4,3},{1,3,2,4},{1,3,4,2},{1,4,2,3},{1,4,3,2}};
        for (int i = 0; i < orders.length; i++) {
            Path dir = root.resolve("case" + i); Fixture f = fixture(dir, orders[i]);
            List<String> reversed = new ArrayList<>(f.entries.keySet()); Collections.reverse(reversed); Map<String, byte[]> reordered = new LinkedHashMap<>(); reversed.forEach(n -> reordered.put(n, f.entries.get(n))); zip(f.aab, reordered);
            String original = CanonicalBundle.hash(f.aab); Path out = run(f, dir, "canonical"); results.add(CanonicalBundle.hash(out));
            assertEquals(original, CanonicalBundle.hash(f.aab)); assertEquals(Files.size(f.aab), Files.size(out));
            try (ZipFile z = new ZipFile(out.toFile())) { for (Map.Entry<String, byte[]> d : f.dex.entrySet()) assertArrayEquals(d.getValue(), z.getInputStream(z.getEntry("base/dex/" + d.getKey())).readAllBytes()); }
            Fixture canonical = new Fixture(f.apk, out, f.dex, f.entries); assertEquals(CanonicalBundle.hash(out), CanonicalBundle.hash(run(canonical, dir, "twice")));
        }
        assertEquals(1, results.size());
    }
    @Test public void mainDexCannotMoveAndMissingExtraOrAmbiguousCodeFails() throws Exception {
        int[][] wrong = {{2,1,3,4},{1,2,3},{1,2,3,3}};
        for (int[] order : wrong) { Path root = Files.createTempDirectory("bad-dex"); reject(fixture(root, order), root); }
        Path root = Files.createTempDirectory("extra-dex"); Fixture f = fixture(root,1,2,3,4); f.entries.put("base/dex/classes5.dex", dex(5)); zip(f.aab,f.entries); reject(f,root);
    }
    @Test public void signedTransparencyFeaturesAndUnsafePathsFailClosed() throws Exception {
        for (String path : List.of("META-INF/KEY.SF", "META-INF/KEY.RSA", "BUNDLE-METADATA/com.android.tools.build.bundletool/code_transparency_signed.jwt", "feature/dex/classes.dex", "base/../escape", "base/assets/other.dex")) {
            Path root=Files.createTempDirectory("unsupported"); Fixture f=fixture(root,1,2,3,4); f.entries.put(path,new byte[]{8}); zip(f.aab,f.entries); reject(f,root);
        }
    }
    @Test public void actualOfficialParserRejectsWrongNamesChecksumsAndMetadata() throws Exception {
        for (int mode=0;mode<5;mode++) {
            Path root=Files.createTempDirectory("bad-profile"); Fixture f=fixture(root,1,2,3,4); Map<String,byte[]> wrong=new LinkedHashMap<>(f.dex);
            if(mode==0)wrong.put("classes3.dex",dex(9));
            if(mode==1){wrong.remove("classes3.dex");wrong.put("classes9.dex",dex(3));}
            byte[] prof=mode==2?new byte[]{'p','r','o',0,'0','1','0',0,0}:profile(wrong,false);
            if(mode==4)ByteBuffer.wrap(prof).order(ByteOrder.LITTLE_ENDIAN).putInt(9,Integer.MAX_VALUE);
            byte[] meta=profile(wrong,true); if(mode==3)meta=new byte[]{'p','r','m',0,'0','0','2',0};
            f.entries.put("BUNDLE-METADATA/com.android.tools.build.profiles/baseline.prof",prof);f.entries.put("BUNDLE-METADATA/com.android.tools.build.profiles/baseline.profm",meta);zip(f.aab,f.entries);
            Map<String,byte[]> apk=new LinkedHashMap<>(f.dex);apk.put("assets/dexopt/baseline.prof",prof);apk.put("assets/dexopt/baseline.profm",meta);zip(f.apk,apk);reject(f,root);
        }
    }
    @Test public void duplicateLocalCentralCrcAndZip64MetadataAreRejected() throws Exception {
        for(int mode=0;mode<10;mode++) {
            Path root=Files.createTempDirectory("bad-record");Fixture f=fixture(root,1,2,3,4);byte[] raw=Files.readAllBytes(f.aab);ByteBuffer b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int end=raw.length-22,central=b.getInt(end+16);
            if(mode==0){b.putShort(central+6,(short)45);}
            if(mode==1){b.putInt(central+16,b.getInt(central+16)^1);}
            if(mode==2){raw[30]='x';}
            if(mode==3){b.putShort(end+4,(short)1);}
            int second = central + 46 + Short.toUnsignedInt(b.getShort(central+28)) + Short.toUnsignedInt(b.getShort(central+30)) + Short.toUnsignedInt(b.getShort(central+32));
            int third = second + 46 + Short.toUnsignedInt(b.getShort(second+28)) + Short.toUnsignedInt(b.getShort(second+30)) + Short.toUnsignedInt(b.getShort(second+32));
            if(mode==4){System.arraycopy(raw,second+46,raw,third+46,Short.toUnsignedInt(b.getShort(second+28)));}
            if(mode==5){b.putInt(second+42,b.getInt(central+42));}
            if(mode==6){int descriptor=30+Short.toUnsignedInt(b.getShort(26))+Short.toUnsignedInt(b.getShort(28))+b.getInt(central+20);b.putInt(descriptor+4,b.getInt(descriptor+4)^1);}
            if(mode==7){b.putShort(central+8,(short)1);}
            if(mode==8){byte[] gap=new byte[raw.length+16];System.arraycopy(raw,0,gap,0,central);System.arraycopy(raw,central,gap,central+16,raw.length-central);ByteBuffer.wrap(gap).order(ByteOrder.LITTLE_ENDIAN).putInt(end+16+16,central+16);raw=gap;}
            if(mode==9){raw=Arrays.copyOf(raw,raw.length-1);}
            Files.write(f.aab,raw);reject(f,root);
        }
    }
    @Test public void inputsRemainIntactAndOutputIsNotPublishedOnFailure() throws Exception {
        Path root=Files.createTempDirectory("inputs");Fixture f=fixture(root,1,2,3,4);String original=CanonicalBundle.hash(f.aab);
        assertThrows(Exception.class,()->CanonicalBundle.write(f.aab,f.apk,f.aab,root.resolve("audit")));assertEquals(original,CanonicalBundle.hash(f.aab));
        Path link=root.resolve("link.aab");Files.createSymbolicLink(link,f.aab);Fixture linked=new Fixture(f.apk,link,f.dex,f.entries);reject(linked,root);assertEquals(original,CanonicalBundle.hash(f.aab));
        Path alias=root.resolve("alias");Files.createSymbolicLink(alias,root);
        assertThrows(Exception.class,()->CanonicalBundle.write(f.aab,f.apk,alias.resolve("raw.aab"),root.resolve("audit-alias")));
        assertEquals(original,CanonicalBundle.hash(f.aab));
        Path audit=root.resolve("audit-input");Files.createDirectories(audit);Files.createLink(audit.resolve("raw-agp.aab"),f.aab);
        assertThrows(Exception.class,()->CanonicalBundle.write(f.aab,f.apk,root.resolve("safe.aab"),audit));assertEquals(original,CanonicalBundle.hash(f.aab));
    }
}
