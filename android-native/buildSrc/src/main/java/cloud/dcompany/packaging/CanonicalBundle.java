package cloud.dcompany.packaging;

import com.android.tools.profgen.ArtProfile;
import com.android.tools.profgen.ArtProfileKt;
import com.android.tools.profgen.DexFile;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/** Bounded unsigned base-bundle transform, not a general ZIP repair utility. */
public final class CanonicalBundle {
    private CanonicalBundle() {}
    private static final long LIMIT = 2L * 1024 * 1024 * 1024;
    private static final int LOCAL = 0x04034b50, CENTRAL = 0x02014b50, END = 0x06054b50;
    private static final Pattern DEX = Pattern.compile("classes(?:[2-9]|[1-9][0-9]+)?\\.dex");
    private static final String PROFILE = "BUNDLE-METADATA/com.android.tools.build.profiles/baseline.prof";
    private static final String METADATA = "BUNDLE-METADATA/com.android.tools.build.profiles/baseline.profm";

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    private static int u16(byte[] b, int at) { return Short.toUnsignedInt(ByteBuffer.wrap(b, at, 2).order(ByteOrder.LITTLE_ENDIAN).getShort()); }
    private static long u32(byte[] b, int at) { return Integer.toUnsignedLong(ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()); }
    private static void put16(byte[] b, int at, int n) { ByteBuffer.wrap(b, at, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short)n); }
    private static void put32(byte[] b, int at, long n) { require(n >= 0 && n < 0xffffffffL, "ZIP32 bound exceeded"); ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).putInt((int)n); }
    private static byte[] read(RandomAccessFile f, long at, int count) throws IOException {
        require(at >= 0 && count >= 0 && at + count <= f.length(), "Truncated ZIP record");
        byte[] bytes = new byte[count]; f.seek(at); f.readFully(bytes); return bytes;
    }
    static String hash(Path path) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) { byte[] buffer = new byte[65536]; for (int n; (n = in.read(buffer)) != -1;) d.update(buffer, 0, n); }
        return HexFormat.of().formatHex(d.digest());
    }
    private static void checkPath(String name) {
        require(!name.isEmpty() && !name.startsWith("/") && !name.contains("\\") && !name.contains("\0")
                && !name.contains("//"), "Unsafe ZIP path");
        for (String part : name.split("/", -1)) require(!part.equals("..") && !part.equals("."), "Unsafe ZIP path");
        require(!name.contains(":"), "Unsupported ZIP path");
    }
    private static void extras(byte[] bytes, boolean apk) {
        // Zipflinger's unsigned APK alignment is zero padding, not a payload.
        if (apk) { boolean zero = true; for (byte b : bytes) zero &= b == 0; if (zero) return; }
        int at = 0;
        while (at < bytes.length) {
            require(at + 4 <= bytes.length, "Truncated ZIP extra");
            int id = u16(bytes, at), length = u16(bytes, at + 2); at += 4;
            require(at + length <= bytes.length && id == 0x5455 && length == 5 && bytes[at] == 1,
                    "Unsupported ZIP extra (including ZIP64)");
            at += length;
        }
    }
    static final class Entry {
        String name, sha;
        long crc, compressed, size, offset, dataOffset, end, types, methods;
        int method, flags;
        byte[] central, local;
        boolean dex;
    }
    static final class Archive implements AutoCloseable {
        final Path path;
        final RandomAccessFile file;
        final ZipFile zip;
        final Map<String, Entry> entries = new LinkedHashMap<>();
        byte[] eocd;
        long centralOffset;
        Archive(Path path, boolean bundle) throws Exception {
            this.path = path;
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path), "Input must be a regular file");
            file = new RandomAccessFile(path.toFile(), "r");
            ZipFile opened = null;
            try {
                require(file.length() <= LIMIT && file.length() >= 22, "Archive size bound");
                long end = file.length() - 22;
                while (end >= Math.max(0, file.length() - 65557)) {
                    byte[] h = read(file, end, 22);
                    if (u32(h, 0) == Integer.toUnsignedLong(END) && end + 22 + u16(h, 20) == file.length()) break;
                    end--;
                }
                require(end >= Math.max(0, file.length() - 65557), "Missing exact ZIP end");
                eocd = read(file, end, (int)(file.length() - end));
                int count = u16(eocd, 10);
                require(u16(eocd, 4) == 0 && u16(eocd, 6) == 0 && count == u16(eocd, 8) && count > 0 && count < 65535, "Multidisk/ZIP64/empty archive");
                centralOffset = u32(eocd, 16);
                require(centralOffset + u32(eocd, 12) == end, "ZIP64/signature/trailing central records unsupported");
                long at = centralOffset, total = 0;
                for (int i = 0; i < count; i++) {
                    byte[] h = read(file, at, 46);
                    require(u32(h, 0) == Integer.toUnsignedLong(CENTRAL), "Invalid central record");
                    int names = u16(h, 28), extra = u16(h, 30), comment = u16(h, 32);
                    byte[] raw = read(file, at, 46 + names + extra + comment); at += raw.length;
                    Entry e = new Entry(); e.central = raw;
                    e.name = new String(raw, 46, names, StandardCharsets.UTF_8); checkPath(e.name);
                    require(Arrays.equals(e.name.getBytes(StandardCharsets.UTF_8), Arrays.copyOfRange(raw, 46, 46 + names)), "Invalid UTF8 ZIP name");
                    require(!entries.containsKey(e.name), "Duplicate ZIP path");
                    e.flags = u16(h, 8); e.method = u16(h, 10); e.crc = u32(h, 16); e.compressed = u32(h, 20); e.size = u32(h, 24); e.offset = u32(h, 42);
                    require((e.flags & ~0x808) == 0 && (e.method == 0 || e.method == 8) && u16(h, 6) <= 20 && u16(h, 34) == 0, "Unsupported ZIP feature");
                    require(e.compressed < 0xffffffffL && e.size < 0xffffffffL && e.offset < centralOffset, "Invalid ZIP32 size/offset");
                    int kind = (int)(u32(h, 38) >>> 16) & 0170000;
                    require(kind == 0 || kind == 0100000 || kind == 0040000, "Symlink/nonregular ZIP member");
                    extras(Arrays.copyOfRange(raw, 46 + names, 46 + names + extra), !bundle);
                    String upper = e.name.toUpperCase(Locale.ROOT);
                    require(!(upper.startsWith("META-INF/") && (upper.matches(".*\\.(SF|RSA|DSA|EC)$") || upper.substring(9).startsWith("SIG-"))), "Signed JAR input");
                    require(!upper.contains("CODE_TRANSPARENCY") && !(upper.startsWith("BUNDLE-METADATA/") && upper.endsWith(".JWT")), "Code transparency input");
                    if (bundle) require(e.name.equals("BundleConfig.pb") || e.name.startsWith("base/") || e.name.startsWith("BUNDLE-METADATA/") || e.name.startsWith("META-INF/"), "Unsupported bundle module");
                    total += e.size; require(total <= LIMIT, "Uncompressed archive size bound"); entries.put(e.name, e);
                }
                require(at == end, "Central directory extent differs");
                List<Entry> physical = new ArrayList<>(entries.values()); physical.sort(Comparator.comparingLong(e -> e.offset));
                long cursor = 0;
                for (int i = 0; i < physical.size(); i++) {
                    Entry e = physical.get(i); require(e.offset == cursor, "Unowned ZIP gap/signing block or overlapping records");
                    byte[] h = read(file, e.offset, 30); require(u32(h, 0) == Integer.toUnsignedLong(LOCAL), "Invalid local record");
                    int n = u16(h, 26), x = u16(h, 28); e.local = read(file, e.offset, 30 + n + x);
                    require(u16(h, 4) <= 20 && u16(h, 6) == e.flags && u16(h, 8) == e.method
                            && Arrays.equals(Arrays.copyOfRange(e.local, 30, 30 + n), e.name.getBytes(StandardCharsets.UTF_8)), "Local/central name, method or flags differ");
                    extras(Arrays.copyOfRange(e.local, 30 + n, e.local.length), !bundle);
                    require(u16(h, 10) == u16(e.central, 12) && u16(h, 12) == u16(e.central, 14), "Local/central timestamp differs");
                    if ((e.flags & 8) == 0) require(u32(h, 14) == e.crc && u32(h, 18) == e.compressed && u32(h, 22) == e.size, "Local ZIP sizes/CRC differ");
                    else require((u32(h, 14) == 0 || u32(h, 14) == e.crc) && (u32(h, 18) == 0 || u32(h, 18) == e.compressed) && (u32(h, 22) == 0 || u32(h, 22) == e.size), "Local descriptor sizes/CRC differ");
                    e.dataOffset = e.offset + e.local.length;
                    e.end = i + 1 < physical.size() ? physical.get(i + 1).offset : centralOffset;
                    long descriptor = e.end - e.dataOffset - e.compressed;
                    if ((e.flags & 8) == 0) require(descriptor == 0, "Unexpected ZIP data trailer");
                    else {
                        require(descriptor == 12 || descriptor == 16, "Invalid data descriptor extent");
                        byte[] d = read(file, e.dataOffset + e.compressed, (int)descriptor); int shift = descriptor == 16 ? 4 : 0;
                        require(shift == 0 || u32(d, 0) == 0x08074b50, "Invalid descriptor signature");
                        require(u32(d, shift) == e.crc && u32(d, shift + 4) == e.compressed && u32(d, shift + 8) == e.size, "Descriptor CRC/size differs");
                    }
                    cursor = e.end;
                }
                require(cursor == centralOffset, "Trailing signing block unsupported");
                opened = new ZipFile(path.toFile()); zip = opened;
                for (Entry e : entries.values()) inspect(e);
            } catch (Exception failure) {
                if (opened != null) opened.close(); file.close(); throw failure;
            }
        }
        private void inspect(Entry e) throws Exception {
            MessageDigest sha = MessageDigest.getInstance("SHA-256"); MessageDigest signature = MessageDigest.getInstance("SHA-1");
            CRC32 crc = new CRC32(); Adler32 adler = new Adler32(); long read = 0; byte[] header = new byte[112];
            e.dex = e.name.endsWith(".dex");
            try (InputStream in = zip.getInputStream(zip.getEntry(e.name))) {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) != -1;) {
                    sha.update(buffer, 0, n); crc.update(buffer, 0, n);
                    if (e.dex) {
                        int copy = (int)Math.min(n, Math.max(0, 112 - read)); if (copy > 0) System.arraycopy(buffer, 0, header, (int)read, copy);
                        int sigStart = (int)Math.min(n, Math.max(0, 32 - read)); signature.update(buffer, sigStart, n - sigStart);
                        int adlerStart = (int)Math.min(n, Math.max(0, 12 - read)); adler.update(buffer, adlerStart, n - adlerStart);
                    }
                    read += n; require(read <= e.size, "Entry expands past declared size");
                }
            }
            require(read == e.size && crc.getValue() == e.crc, "ZIP payload size/CRC mismatch"); e.sha = HexFormat.of().formatHex(sha.digest());
            if (e.dex) {
                String magic = new String(header, 0, 8, StandardCharsets.ISO_8859_1);
                require(Set.of("dex\n035\0", "dex\n037\0", "dex\n038\0", "dex\n039\0", "dex\n040\0").contains(magic)
                        && u32(header, 32) == e.size && u32(header, 36) == 112 && u32(header, 40) == 0x12345678,
                        "Unsupported DEX header/version");
                require(Arrays.equals(Arrays.copyOfRange(header, 12, 32), signature.digest()) && u32(header, 8) == adler.getValue(), "DEX SHA1/Adler32 mismatch");
                e.types = u32(header, 64); e.methods = u32(header, 88);
            }
            if (e.name.equalsIgnoreCase("META-INF/MANIFEST.MF")) require(!new String(bytes(e.name, 1024 * 1024), StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT).contains("-digest:"), "Signature manifest input");
        }
        byte[] bytes(String name, int maximum) throws IOException {
            Entry e = entries.get(name); require(e != null && e.size <= maximum, "Missing or oversized profile/manifest");
            try (InputStream in = zip.getInputStream(zip.getEntry(name))) { return in.readAllBytes(); }
        }
        @Override public void close() throws IOException { try { zip.close(); } finally { file.close(); } }
    }
    private static SortedMap<String, Entry> dex(Archive archive, boolean bundle) {
        String prefix = bundle ? "base/dex/" : ""; SortedMap<String, Entry> result = new TreeMap<>(); Set<String> hashes = new HashSet<>(); Set<Integer> numbers = new HashSet<>();
        for (Entry e : archive.entries.values()) if (e.dex) {
            require(e.name.startsWith(prefix), "Unsupported DEX module"); String name = e.name.substring(prefix.length());
            require(DEX.matcher(name).matches(), "Unsupported DEX path/name"); int number = name.equals("classes.dex") ? 1 : Integer.parseInt(name.substring(7, name.length() - 4));
            require(numbers.add(number) && hashes.add(e.sha), "Ambiguous duplicate DEX payload"); result.put(name, e);
        }
        require(!result.isEmpty(), "No DEX payloads");
        for (int i = 1; i <= result.size(); i++) require(numbers.contains(i), "Noncontiguous DEX names"); return result;
    }
    private static void profiles(Archive bundle, Archive apk, Map<String, Entry> dex) throws Exception {
        byte[] profile = bundle.bytes(PROFILE, 32 * 1024 * 1024), metadata = bundle.bytes(METADATA, 32 * 1024 * 1024);
        require(Arrays.equals(profile, apk.bytes("assets/dexopt/baseline.prof", 32 * 1024 * 1024)) && Arrays.equals(metadata, apk.bytes("assets/dexopt/baseline.profm", 32 * 1024 * 1024)), "Bundle/APK profiles differ");
        require(profile.length >= 8 && Arrays.equals(Arrays.copyOf(profile, 8), new byte[]{'p','r','o',0,'0','1','0',0})
                && metadata.length >= 8 && Arrays.equals(Arrays.copyOf(metadata, 8), new byte[]{'p','r','m',0,'0','0','2',0}), "Unsupported profile format");
        // The official format embeds another compressed stream. Bound its
        // declared inflated size before invoking profgen on malformed input.
        require(profile.length >= 17 && u32(profile, 9) <= 32 * 1024 * 1024 && u32(profile, 13) == profile.length - 17
                && metadata.length >= 18 && u32(metadata, 10) <= 32 * 1024 * 1024 && u32(metadata, 14) == metadata.length - 18,
                "Oversized or inconsistent compressed profile body");
        ArtProfile p = ArtProfileKt.ArtProfile(new ByteArrayInputStream(profile)); ArtProfile m = ArtProfileKt.ArtProfile(new ByteArrayInputStream(metadata));
        require(p != null && m != null, "Official profgen could not decode profile");
        Set<String> names = new HashSet<>(), metaNames = new HashSet<>();
        for (DexFile d : p.getProfileData().keySet()) {
            Entry reference = dex.get(d.getName()); require(reference != null && names.add(d.getName()) && reference.crc == d.getDexChecksum()
                    && reference.methods == d.getHeader$profgen().getMethodIds().getSize(), "Profile DEX name/CRC32/method count differs");
        }
        for (DexFile d : m.getProfileData().keySet()) {
            Entry reference = dex.get(d.getName()); require(reference != null && metaNames.add(d.getName()) && d.getDexChecksum() == 0
                    && reference.types == d.getHeader$profgen().getTypeIds().getSize(), "Metadata DEX name/type count differs");
        }
        require(!names.isEmpty() && names.equals(metaNames), "Profile/metadata names differ");
    }
    private static byte[] rename(byte[] record, int fixed, int nameOffset, String name) {
        int old = u16(record, nameOffset); byte[] replacement = name.getBytes(StandardCharsets.UTF_8);
        byte[] renamed = new byte[record.length - old + replacement.length]; System.arraycopy(record, 0, renamed, 0, fixed);
        put16(renamed, nameOffset, replacement.length); System.arraycopy(replacement, 0, renamed, fixed, replacement.length);
        System.arraycopy(record, fixed + old, renamed, fixed + replacement.length, record.length - fixed - old); return renamed;
    }
    private static void copy(RandomAccessFile in, OutputStream out, long from, long count) throws IOException {
        in.seek(from); byte[] buffer = new byte[65536];
        while (count > 0) { int n = in.read(buffer, 0, (int)Math.min(count, buffer.length)); if (n < 0) throw new EOFException(); out.write(buffer, 0, n); count -= n; }
    }
    public static void write(Path bundle, Path apk, Path output, Path audit) throws Exception {
        Files.createDirectories(output.toAbsolutePath().getParent()); Files.createDirectories(audit);
        Path outIdentity = output.toAbsolutePath().getParent().toRealPath().resolve(output.getFileName());
        Path raw = audit.resolve("raw-agp.aab");
        Path rawIdentity = audit.toRealPath().resolve("raw-agp.aab");
        for (Path input : List.of(bundle, apk)) {
            Path real = input.toRealPath();
            require(!real.equals(outIdentity) && !real.equals(rawIdentity)
                    && !(Files.exists(output) && Files.isSameFile(input, output))
                    && !(Files.exists(raw) && Files.isSameFile(input, raw)), "Output/audit replaces input");
        }
        require(!outIdentity.equals(rawIdentity) && !Files.isSymbolicLink(output) && !Files.isSymbolicLink(raw), "Output/audit alias");
        String before = hash(bundle), apkHash = hash(apk);
        Files.copy(bundle, raw, StandardCopyOption.REPLACE_EXISTING);
        Path temp = Files.createTempFile(output.toAbsolutePath().getParent(), "canonical-", ".tmp");
        try (Archive ab = new Archive(bundle, true); Archive ap = new Archive(apk, false)) {
            SortedMap<String, Entry> ad = dex(ap, false), bd = dex(ab, true);
            require(ad.size() == bd.size() && ad.get("classes.dex").sha.equals(bd.get("classes.dex").sha), "Main DEX or DEX count differs");
            Map<String, String> targetByHash = new HashMap<>(); ad.forEach((n, e) -> targetByHash.put(e.sha, "base/dex/" + n));
            Map<String, Entry> expected = new TreeMap<>();
            for (Entry e : ab.entries.values()) { String name = e.dex ? targetByHash.get(e.sha) : e.name; require(name != null && expected.put(name, e) == null, "Missing/extra or ambiguous DEX mapping"); }
            profiles(ab, ap, ad);
            List<byte[]> central = new ArrayList<>(); long cursor = 0; StringBuilder report = new StringBuilder("before\tafter\tsha256\tcompressed_bytes\tuncompressed_bytes\n");
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                for (Map.Entry<String, Entry> item : expected.entrySet()) {
                    String name = item.getKey(); Entry e = item.getValue(); byte[] local = e.name.equals(name) ? e.local : rename(e.local, 30, 26, name);
                    out.write(local); copy(ab.file, out, e.dataOffset, e.end - e.dataOffset);
                    byte[] c = e.name.equals(name) ? e.central.clone() : rename(e.central, 46, 28, name); put32(c, 42, cursor); central.add(c);
                    cursor += local.length + e.end - e.dataOffset;
                    report.append(e.name).append('\t').append(name).append('\t').append(e.sha).append('\t').append(e.compressed).append('\t').append(e.size).append('\n');
                }
                long start = cursor; for (byte[] c : central) { out.write(c); cursor += c.length; }
                byte[] end = ab.eocd.clone(); put32(end, 12, cursor - start); put32(end, 16, start); out.write(end);
            }
            try (Archive after = new Archive(temp, true)) {
                require(after.entries.keySet().equals(expected.keySet()), "Canonical output entry set differs");
                for (Map.Entry<String, Entry> item : expected.entrySet()) {
                    Entry e = item.getValue(), now = after.entries.get(item.getKey()); require(e.sha.equals(now.sha) && e.crc == now.crc && e.size == now.size && e.compressed == now.compressed, "Canonical payload differs");
                    // Verify raw compressed bytes plus descriptor, not only inflated content.
                    long n = e.end - e.dataOffset; require(n == now.end - now.dataOffset, "Canonical raw stream extent differs");
                    for (long at = 0; at < n; at += 65536) require(Arrays.equals(read(ab.file, e.dataOffset + at, (int)Math.min(65536, n - at)), read(after.file, now.dataOffset + at, (int)Math.min(65536, n - at))), "Compressed stream changed");
                }
                profiles(after, ap, ad); dex(after, true);
            }
            require(before.equals(hash(bundle)) && apkHash.equals(hash(apk)), "Input changed during transformation");
            Files.writeString(audit.resolve("entry-map.tsv"), report, StandardCharsets.UTF_8);
            Files.writeString(audit.resolve("SHA256SUMS"), before + "  raw-agp.aab\n" + apkHash + "  reference.apk\n" + hash(temp) + "  canonical.aab\n", StandardCharsets.UTF_8);
            Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
