package cloud.dcompany.erp.ui.theme;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

/**
 * Test-APK-only provider implemented without Kotlin runtime dependencies because Android hosts it
 * in the separate test-package process. Every externally reachable operation checks the caller
 * before parsing arguments or disclosing fixture state.
 */
public final class WorkspaceBackgroundFixtureProvider extends ContentProvider {
    public static final String AUTHORITY = "cloud.dcompany.erp.background-fixtures";
    private static final String TARGET_PACKAGE = "cloud.dcompany.erp";
    private static final String CREATE_METHOD = "create-fixture";
    private static final String RESULT_URI_KEY = "uri";
    private static final String FIXTURE_DIRECTORY_NAME = "background-fixtures-r4";
    private static final String UNSUPPORTED = "Fixture provider operation is not supported";
    private static final String UNAVAILABLE = "Fixture is unavailable";
    private static final int MAXIMUM_FIXTURES = 32;
    private static final long FIXTURE_TTL_MILLIS = 120_000L;
    private static final Object LOCK = new Object();
    private static final Map<String, Record> RECORDS = new HashMap<>();
    private static final Map<String, Record> CONTROLLED = new HashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private File fixtureDirectory;

    @Override
    public boolean onCreate() {
        fixtureDirectory = new File(providerContext().getCacheDir(), FIXTURE_DIRECTORY_NAME);
        if (!fixtureDirectory.isDirectory() && !fixtureDirectory.mkdirs()) {
            throw new IllegalStateException("Could not initialize fixture storage");
        }
        File[] children = fixtureDirectory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.getName().matches("[0-9a-f]{32}\\.fixture")) child.delete();
            }
        }
        synchronized (LOCK) {
            for (Record record : CONTROLLED.values()) {
                record.gate.failed.set(true);
                record.gate.release.countDown();
            }
            CONTROLLED.clear();
            RECORDS.clear();
        }
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceCaller();
        if (extras != null && !extras.isEmpty()) {
            throw new IllegalArgumentException(UNSUPPORTED);
        }
        if ("controlled-fixture-state".equals(method)
                || "release-controlled-fixture".equals(method)
                || "dispose-controlled-fixture".equals(method)) {
            return controlledFixture(method, arg);
        }
        if (!CREATE_METHOD.equals(method)) throw new IllegalArgumentException(UNSUPPORTED);
        synchronized (LOCK) {
            removeExpiredLocked();
            int count = RECORDS.size();
            for (String token : CONTROLLED.keySet()) if (!RECORDS.containsKey(token)) count++;
            if (count >= MAXIMUM_FIXTURES) {
                throw new IllegalStateException("Fixture capacity is exhausted");
            }
            String token = newTokenLocked();
            File file = new File(fixtureDirectory, token + ".fixture");
            Record record = createRecipe(arg, file);
            RECORDS.put(token, record);
            if (record.gate != null) CONTROLLED.put(token, record);
            Bundle result = new Bundle();
            result.putString(RESULT_URI_KEY, "content://" + AUTHORITY + "/fixture/" + token);
            return result;
        }
    }

    private Bundle controlledFixture(String method, String token) {
        if (token == null || !token.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException(UNAVAILABLE);
        }
        synchronized (LOCK) {
            removeExpiredLocked();
            Record record = CONTROLLED.get(token);
            if (record == null) throw new IllegalArgumentException(UNAVAILABLE);
            Gate gate = record.gate;
            if ("release-controlled-fixture".equals(method)) gate.release.countDown();
            if ("dispose-controlled-fixture".equals(method)) {
                if (gate.entered.getCount() == 0L && gate.exited.getCount() != 0L) {
                    throw new IllegalStateException("Cannot dispose an active fixture provider");
                }
                CONTROLLED.remove(token);
                RECORDS.remove(token);
                record.file.delete();
            }
            Bundle result = new Bundle();
            result.putBoolean("entered", gate.entered.getCount() == 0L);
            result.putBoolean("released", gate.release.getCount() == 0L);
            result.putBoolean("exited", gate.exited.getCount() == 0L);
            result.putBoolean("failed", gate.failed.get());
            return result;
        }
    }

    @Override
    public Bundle call(String authority, String method, String arg, Bundle extras) {
        enforceCaller();
        if (!AUTHORITY.equals(authority)) throw new IllegalArgumentException(UNSUPPORTED);
        return call(method, arg, extras);
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        return openFixture(uri, mode);
    }

    @Override
    public AssetFileDescriptor openAssetFile(
            Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
        return openFixture(uri, mode);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return openFixture(uri, mode).getParcelFileDescriptor();
    }

    @Override
    public ParcelFileDescriptor openFile(
            Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
        return openFixture(uri, mode).getParcelFileDescriptor();
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(
            Uri uri, String mimeTypeFilter, Bundle opts) throws FileNotFoundException {
        return openFixture(uri, "r");
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(
            Uri uri, String mimeTypeFilter, Bundle opts, CancellationSignal signal)
            throws FileNotFoundException {
        return openFixture(uri, "r");
    }

    @Override
    public String getType(Uri uri) {
        enforceCaller();
        try {
            return requireRecord(uri, false).mimeType;
        } catch (FileNotFoundException failure) {
            throw new IllegalArgumentException(UNAVAILABLE);
        }
    }

    @Override public String getTypeAnonymous(Uri uri) { return unsupported(); }
    @Override public String[] getStreamTypes(Uri uri, String mimeTypeFilter) { return unsupported(); }

    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return unsupported();
    }

    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder,
            CancellationSignal cancellationSignal) {
        return unsupported();
    }

    @Override
    public Cursor query(
            Uri uri, String[] projection, Bundle queryArgs, CancellationSignal cancellationSignal) {
        return unsupported();
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return unsupported(); }
    @Override public Uri insert(Uri uri, ContentValues values, Bundle extras) { return unsupported(); }
    @Override public int bulkInsert(Uri uri, ContentValues[] values) { return unsupported(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return unsupported(); }
    @Override public int delete(Uri uri, Bundle extras) { return unsupported(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return unsupported(); }
    @Override public int update(Uri uri, ContentValues values, Bundle extras) { return unsupported(); }

    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        return unsupported();
    }

    @Override
    public ContentProviderResult[] applyBatch(
            String authority, ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        return unsupported();
    }

    @Override public Uri canonicalize(Uri uri) { return unsupported(); }
    @Override public Uri uncanonicalize(Uri uri) { return unsupported(); }
    @Override public boolean refresh(Uri uri, Bundle args, CancellationSignal signal) { return unsupported(); }

    public static boolean callerAllowed(
            int ownUid, int callerUid, String[] callerPackages, boolean sameSignature) {
        if (callerUid == ownUid) return true;
        return callerPackages != null
                && callerPackages.length == 1
                && TARGET_PACKAGE.equals(callerPackages[0])
                && sameSignature;
    }

    private AssetFileDescriptor openFixture(Uri uri, String mode) throws FileNotFoundException {
        enforceCaller();
        if (!"r".equals(mode)) throw new FileNotFoundException(UNAVAILABLE);
        Record record = requireRecord(uri, true);
        try {
            if (record.gate != null) {
                record.gate.entered.countDown();
                // The cancellation signal is deliberately ignored. Only the test releases this
                // provider; the fail-safe is reported as a failure rather than an implicit release.
                if (!record.gate.release.await(15L, TimeUnit.SECONDS)) {
                    record.gate.failed.set(true);
                    record.file.delete();
                    throw new FileNotFoundException("Controlled fixture release timed out");
                }
            }
            if (record.delayMillis > 0L) {
                Thread.sleep(record.delayMillis);
            }
            ParcelFileDescriptor descriptor =
                    ParcelFileDescriptor.open(record.file, ParcelFileDescriptor.MODE_READ_ONLY);
            long length = record.file.length();
            record.file.delete();
            return new AssetFileDescriptor(descriptor, 0L, length);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (record.gate != null) record.gate.failed.set(true);
            record.file.delete();
            throw new FileNotFoundException(UNAVAILABLE);
        } finally {
            if (record.gate != null) record.gate.exited.countDown();
        }
    }

    private Record requireRecord(Uri uri, boolean consume) throws FileNotFoundException {
        String token = token(uri);
        synchronized (LOCK) {
            Record record = consume ? RECORDS.remove(token) : RECORDS.get(token);
            if (record == null
                    || SystemClock.elapsedRealtime() - record.createdAtMillis > FIXTURE_TTL_MILLIS
                    || !record.file.isFile()) {
                RECORDS.remove(token);
                if (record != null) record.file.delete();
                throw new FileNotFoundException(UNAVAILABLE);
            }
            try {
                if (!fixtureDirectory.getCanonicalFile().equals(record.file.getCanonicalFile().getParentFile())) {
                    if (consume) record.file.delete();
                    throw new FileNotFoundException(UNAVAILABLE);
                }
            } catch (IOException failure) {
                if (consume) record.file.delete();
                throw new FileNotFoundException(UNAVAILABLE);
            }
            if (consume && record.gate != null) record.gate.entered.countDown();
            return record;
        }
    }

    private String token(Uri uri) throws FileNotFoundException {
        if (uri == null || uri.getPathSegments().size() != 2
                || !"fixture".equals(uri.getPathSegments().get(0))) {
            throw new FileNotFoundException(UNAVAILABLE);
        }
        String token = uri.getPathSegments().get(1);
        if (!token.matches("[0-9a-f]{32}")) throw new FileNotFoundException(UNAVAILABLE);
        return token;
    }

    private void enforceCaller() {
        int callerUid = Binder.getCallingUid();
        int ownUid = Process.myUid();
        PackageManager packageManager = providerContext().getPackageManager();
        String[] packages = packageManager.getPackagesForUid(callerUid);
        boolean sameSignature =
                packageManager.checkSignatures(callerUid, ownUid) == PackageManager.SIGNATURE_MATCH;
        if (!callerAllowed(ownUid, callerUid, packages, sameSignature)) {
            throw new SecurityException("Fixture provider caller is not authorized");
        }
    }

    private <T> T unsupported() {
        enforceCaller();
        throw new UnsupportedOperationException(UNSUPPORTED);
    }

    private Context providerContext() {
        Context value = getContext();
        if (value == null) throw new IllegalStateException("Provider is not attached");
        return value;
    }

    private Record createRecipe(String recipe, File file) {
        Spec spec;
        switch (recipe == null ? "" : recipe) {
            case "ALPHA_PNG": spec = new Spec(2560, 1080, Bitmap.CompressFormat.PNG, 0x6E245480); break;
            case "VALID_JPEG": spec = new Spec(2560, 1080, Bitmap.CompressFormat.JPEG, 0xFF245480); break;
            case "PORTRAIT_JPEG": spec = new Spec(1080, 2560, Bitmap.CompressFormat.JPEG, 0xFF245480); break;
            case "TRUNCATED_JPEG": spec = new Spec(2560, 1080, Bitmap.CompressFormat.JPEG, 0xFF245480, 2, false, ExifInterface.ORIENTATION_NORMAL, 0L); break;
            case "TRUNCATED_PNG": spec = new Spec(2560, 1080, Bitmap.CompressFormat.PNG, 0xFF245480, 8, false, ExifInterface.ORIENTATION_NORMAL, 0L); break;
            case "ANIMATED_PNG": spec = new Spec(2560, 1080, Bitmap.CompressFormat.PNG, 0xFF245480, 0, true, ExifInterface.ORIENTATION_NORMAL, 0L); break;
            case "TOO_SMALL_JPEG": spec = new Spec(2048, 1024, Bitmap.CompressFormat.JPEG, 0xFF245480); break;
            case "EXIF_ROTATED_JPEG": spec = new Spec(1080, 2560, Bitmap.CompressFormat.JPEG, 0xFF245480, 0, false, ExifInterface.ORIENTATION_ROTATE_90, 0L); break;
            case "DELAYED_VALID_JPEG": spec = new Spec(2560, 1080, Bitmap.CompressFormat.JPEG, 0xFF245480, 0, false, ExifInterface.ORIENTATION_NORMAL, 700L); break;
            case "CONTROLLED_VALID_JPEG": spec = new Spec(2560, 1080, Bitmap.CompressFormat.JPEG, 0xFF245480); break;
            case "RETRY_VALID_JPEG": spec = new Spec(2560, 1080, Bitmap.CompressFormat.JPEG, 0xFF70512A); break;
            case "PERSISTENCE_HOME_JPEG": spec = new Spec(2560, 1080, Bitmap.CompressFormat.JPEG, 0xFF315B7A); break;
            case "PERSISTENCE_WORKSPACE_PNG": spec = new Spec(2560, 1080, Bitmap.CompressFormat.PNG, 0xFF7A4A31); break;
            case "EDGE_4096_JPEG": spec = new Spec(4096, 1080, Bitmap.CompressFormat.JPEG, 0xFF315B7A); break;
            case "EDGE_4097_JPEG": spec = new Spec(4097, 1080, Bitmap.CompressFormat.JPEG, 0xFF7A4A31); break;
            case "BROKEN_PNG":
                writeBytes(file, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
                return new Record(file, "image/png", 0L, SystemClock.elapsedRealtime(), null);
            default: throw new IllegalArgumentException(UNSUPPORTED);
        }
        writeBitmap(file, spec);
        String mime = spec.format == Bitmap.CompressFormat.PNG ? "image/png" : "image/jpeg";
        return new Record(file, mime, spec.delayMillis, SystemClock.elapsedRealtime(),
                "CONTROLLED_VALID_JPEG".equals(recipe) ? new Gate() : null);
    }

    private void writeBitmap(File file, Spec spec) {
        Bitmap bitmap = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(spec.color);
            try (FileOutputStream output = new FileOutputStream(file)) {
                if (!bitmap.compress(spec.format, 94, output)) throw new IOException("encode failed");
            }
            if (spec.format == Bitmap.CompressFormat.JPEG
                    && spec.orientation != ExifInterface.ORIENTATION_NORMAL) {
                ExifInterface exif = new ExifInterface(file.getAbsolutePath());
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(spec.orientation));
                exif.saveAttributes();
            }
            if (spec.truncateTail > 0) {
                try (RandomAccessFile data = new RandomAccessFile(file, "rw")) {
                    data.setLength(Math.max(0L, data.length() - spec.truncateTail));
                }
            }
            if (spec.animatedPng) insertPngAnimationControl(file);
        } catch (IOException failure) {
            file.delete();
            throw new IllegalStateException("Could not create fixed fixture", failure);
        } finally {
            bitmap.recycle();
        }
    }

    private void insertPngAnimationControl(File file) throws IOException {
        byte[] source = readBytes(file);
        if (source.length <= 33) throw new IOException("invalid fixed PNG");
        ByteArrayOutputStream chunkBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(chunkBytes)) {
            byte[] type = new byte[] {'a', 'c', 'T', 'L'};
            byte[] payload = new byte[8];
            payload[3] = 1;
            output.writeInt(payload.length);
            output.write(type);
            output.write(payload);
            CRC32 crc = new CRC32();
            crc.update(type);
            crc.update(payload);
            output.writeInt((int) crc.getValue());
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(source, 0, 33);
            output.write(chunkBytes.toByteArray());
            output.write(source, 33, source.length - 33);
        }
    }

    private static byte[] readBytes(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private static void writeBytes(File file, byte[] bytes) {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        } catch (IOException failure) {
            file.delete();
            throw new IllegalStateException("Could not create fixed fixture", failure);
        }
    }

    private void removeExpiredLocked() {
        long now = SystemClock.elapsedRealtime();
        ArrayList<String> expired = new ArrayList<>();
        for (Map.Entry<String, Record> entry : RECORDS.entrySet()) {
            if (now - entry.getValue().createdAtMillis > FIXTURE_TTL_MILLIS) {
                entry.getValue().file.delete();
                expired.add(entry.getKey());
            }
        }
        for (String token : expired) RECORDS.remove(token);
        expired.clear();
        for (Map.Entry<String, Record> entry : CONTROLLED.entrySet()) {
            if (now - entry.getValue().createdAtMillis > FIXTURE_TTL_MILLIS) {
                entry.getValue().gate.failed.set(true);
                entry.getValue().gate.release.countDown();
                entry.getValue().file.delete();
                expired.add(entry.getKey());
            }
        }
        for (String token : expired) CONTROLLED.remove(token);
    }

    private String newTokenLocked() {
        while (true) {
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            char[] token = new char[32];
            for (int i = 0; i < bytes.length; i++) {
                int value = bytes[i] & 0xff;
                token[i * 2] = HEX[value >>> 4];
                token[i * 2 + 1] = HEX[value & 0x0f];
            }
            String value = new String(token);
            if (!RECORDS.containsKey(value) && !CONTROLLED.containsKey(value)) return value;
        }
    }

    private static final class Record {
        final File file;
        final String mimeType;
        final long delayMillis;
        final long createdAtMillis;
        final Gate gate;

        Record(File file, String mimeType, long delayMillis, long createdAtMillis, Gate gate) {
            this.file = file;
            this.mimeType = mimeType;
            this.delayMillis = delayMillis;
            this.createdAtMillis = createdAtMillis;
            this.gate = gate;
        }
    }

    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);
        final AtomicBoolean failed = new AtomicBoolean(false);
    }

    private static final class Spec {
        final int width;
        final int height;
        final Bitmap.CompressFormat format;
        final int color;
        final int truncateTail;
        final boolean animatedPng;
        final int orientation;
        final long delayMillis;

        Spec(int width, int height, Bitmap.CompressFormat format, int color) {
            this(width, height, format, color, 0, false, ExifInterface.ORIENTATION_NORMAL, 0L);
        }

        Spec(int width, int height, Bitmap.CompressFormat format, int color,
             int truncateTail, boolean animatedPng, int orientation, long delayMillis) {
            this.width = width;
            this.height = height;
            this.format = format;
            this.color = color;
            this.truncateTail = truncateTail;
            this.animatedPng = animatedPng;
            this.orientation = orientation;
            this.delayMillis = delayMillis;
        }
    }
}
