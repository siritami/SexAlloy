package io.github.nexalloy.revanced.zalo.calls;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-process finalization for captured Zalo call audio: repair native WAV header
 * → transcode to M4A → publish to the shared MediaStore, all under Zalo's UID.
 *
 * <p>Logs every stage to logcat tag {@value #TAG}. On failure it KEEPS the source
 * WAV (renamed to {@code *.failed.wav} beside the cache) so you can confirm whether
 * capture worked, instead of deleting the evidence.
 */
public final class CallRecordingOutput {
    public static final String TAG = "NexAlloyCall";

    static final String TEMP_DIRECTORY = "nexalloy_call_recordings";

    // "Recordings/" is only a valid MediaStore top-level dir on Android 12 (API 31)+.
    // On 10/11 an insert into it returns null / throws, so fall back to "Music/".
    private static final String DIR_MODERN = "Recordings/Zalo Call Recordings";
    private static final String DIR_LEGACY = "Music/Zalo Call Recordings";

    private static final Pattern PART_NAME = Pattern.compile(
            "zalo-call-(\\d{13})-(incoming|outgoing|unknown)-([0-9a-f]{8})\\.part");
    private static final ExecutorService FINALIZER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NexAlloyCallFinalizer");
        t.setDaemon(true);
        return t;
    });
    private static final Set<String> QUEUED = Collections.synchronizedSet(new HashSet<>());

    /** Optional status sink so the patch can drive recording notifications. */
    public interface StatusListener {
        void onSaved();

        void onFailed();
    }

    private CallRecordingOutput() {
    }

    public static File tempDirectory(Context context) {
        File directory = new File(context.getCacheDir(), TEMP_DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) {
            Log.w(TAG, "Could not create temp dir " + directory);
        }
        return directory;
    }

    public static String newPendingName(long startedAt, String direction) {
        String safeDirection = safeDirection(direction);
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return String.format(Locale.US, "zalo-call-%013d-%s-%s.part",
                Math.max(0L, startedAt), safeDirection, nonce);
    }

    public static boolean isNativeImportReady(File file) {
        return file != null && file.isFile() && CallRecordingTranscoder.isPcmWave(file);
    }

    public static boolean repairNativeImport(File file) {
        return file != null && file.isFile() && CallRecordingTranscoder.repairHeader(file);
    }

    public static void finalizeRecording(
            Context context,
            File wavFile,
            long startedAt,
            String direction,
            String peerUid,
            String fallbackName,
            String fallbackPhone,
            StatusListener listener) {
        if (context == null || wavFile == null) {
            Log.w(TAG, "finalizeRecording: null context/wav");
            if (listener != null) listener.onFailed();
            return;
        }
        final Context app = context.getApplicationContext();
        final String key = wavFile.getAbsolutePath();
        if (!QUEUED.add(key)) {
            Log.i(TAG, "finalizeRecording: already queued " + wavFile.getName());
            return;
        }
        FINALIZER.execute(() -> {
            try {
                long size = wavFile.isFile() ? wavFile.length() : -1L;
                Log.i(TAG, "finalize start: " + wavFile.getName() + " bytes=" + size
                        + " uid=" + peerUid + " dir=" + direction);
                CallRecordingContacts.Result contact =
                        CallRecordingContacts.resolve(app, peerUid, fallbackName, fallbackPhone);
                boolean ok = convertAndPublish(app, wavFile, startedAt,
                        contact.displayName, contact.phoneNumber);
                if (ok) {
                    Log.i(TAG, "finalize OK: saved to MediaStore");
                    if (listener != null) listener.onSaved();
                } else {
                    File kept = preserveFailed(wavFile);
                    Log.e(TAG, "finalize FAILED: WAV kept at " + kept);
                    if (listener != null) listener.onFailed();
                }
            } catch (Throwable t) {
                File kept = preserveFailed(wavFile);
                Log.e(TAG, "finalize EXCEPTION: WAV kept at " + kept, t);
                if (listener != null) listener.onFailed();
            } finally {
                QUEUED.remove(key);
            }
        });
    }

    public static void recoverPending(Context context, StatusListener listener) {
        final Context app = context.getApplicationContext();
        FINALIZER.execute(() -> {
            File[] pending = tempDirectory(app).listFiles(
                    (ignored, name) -> name.endsWith(".part"));
            if (pending == null || pending.length == 0) {
                return;
            }
            Log.i(TAG, "recoverPending: " + pending.length + " leftover WAV(s)");
            for (File file : pending) {
                if (!isNativeImportReady(file) && !repairNativeImport(file)) {
                    Log.w(TAG, "recoverPending: not valid PCM, skipping " + file.getName());
                    continue;
                }
                Matcher matcher = PART_NAME.matcher(file.getName());
                long startedAt = matcher.matches() ? parseLong(matcher.group(1)) : file.lastModified();
                boolean ok = convertAndPublish(app, file, startedAt, "Zalo contact", "");
                if (!ok) preserveFailed(file);
                if (listener != null) {
                    if (ok) listener.onSaved();
                    else listener.onFailed();
                }
            }
        });
    }

    private static boolean convertAndPublish(
            Context context, File wavFile, long startedAt, String displayName, String phoneNumber) {
        if (wavFile == null || !wavFile.isFile() || wavFile.length() <= 0L) {
            Log.e(TAG, "convert: source WAV missing or empty "
                    + (wavFile == null ? "null" : wavFile.getName()
                    + " bytes=" + (wavFile.isFile() ? wavFile.length() : -1L)));
            return false;
        }
        if (!isNativeImportReady(wavFile)) {
            boolean repaired = repairNativeImport(wavFile);
            Log.i(TAG, "convert: header repair " + (repaired ? "OK" : "FAILED")
                    + " for " + wavFile.getName());
            if (!repaired) return false;
        }
        File encoded = new File(wavFile.getParentFile(), wavFile.getName() + ".m4a.tmp");
        try {
            CallRecordingTranscoder.wavToM4a(wavFile, encoded);
            Log.i(TAG, "convert: transcoded to " + encoded.length() + " bytes");
            Uri saved = publish(context, encoded,
                    buildDisplayName(startedAt, displayName, phoneNumber));
            if (saved == null) {
                Log.e(TAG, "convert: MediaStore publish returned null");
                return false;
            }
            Log.i(TAG, "convert: published " + saved);
            wavFile.delete();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "convert: transcode/publish failed", t);
            return false;
        } finally {
            encoded.delete();
        }
    }

    private static Uri publish(Context context, File source, String displayName) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IOException("Shared call recordings require Android 10 or newer");
        }
        // Prefer Recordings/ on API 31+, else Music/. If the preferred path is rejected
        // (older API, OEM quirk), retry with the other before giving up.
        boolean modernFirst = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        String primary = modernFirst ? DIR_MODERN : DIR_LEGACY;
        String secondary = modernFirst ? DIR_LEGACY : DIR_MODERN;
        Uri uri = insertInto(context, source, displayName, primary);
        if (uri == null) {
            Log.w(TAG, "publish: insert into '" + primary + "' failed, trying '" + secondary + "'");
            uri = insertInto(context, source, displayName, secondary);
        }
        return uri;
    }

    private static Uri insertInto(Context context, File source, String displayName, String relPath) {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Audio.Media.TITLE,
                displayName.substring(0, displayName.length() - ".m4a".length()));
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, relPath);
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri;
        try {
            uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        } catch (Throwable t) {
            Log.e(TAG, "insert threw for '" + relPath + "'", t);
            return null;
        }
        if (uri == null) {
            return null;
        }
        try (InputStream input = new FileInputStream(source);
             OutputStream output = resolver.openOutputStream(uri, "w")) {
            if (output == null) {
                throw new IOException("Shared output unavailable");
            }
            copy(input, output);
        } catch (Throwable t) {
            resolver.delete(uri, null, null);
            Log.e(TAG, "insert: copy failed for '" + relPath + "'", t);
            return null;
        }
        ContentValues complete = new ContentValues();
        complete.put(MediaStore.Audio.Media.IS_PENDING, 0);
        resolver.update(uri, complete, null, null);
        return uri;
    }

    /** Rename a WAV we could not publish so the next run won't retry it, but keep it for inspection. */
    private static File preserveFailed(File wavFile) {
        if (wavFile == null || !wavFile.isFile()) return null;
        File kept = new File(wavFile.getParentFile(),
                wavFile.getName().replace(".part", "") + ".failed.wav");
        return wavFile.renameTo(kept) ? kept : wavFile;
    }

    static String buildDisplayName(long startedAt, String displayName, String phoneNumber) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US)
                .format(new Date(startedAt > 0L ? startedAt : System.currentTimeMillis()));
        StringBuilder name = new StringBuilder(timestamp).append(" - ")
                .append(sanitizeFilename(safeDisplayName(displayName)));
        String safePhone = safePhone(phoneNumber);
        if (!safePhone.isEmpty()) {
            name.append(" - ").append(safePhone);
        }
        return name.append(".m4a").toString();
    }

    private static String sanitizeFilename(String value) {
        String clean = value == null ? "" : value
                .replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_")
                .replaceAll("\\s+", " ").trim();
        while (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1).trim();
        }
        if (clean.isEmpty()) {
            clean = "Zalo contact";
        }
        return clean.length() > 80 ? clean.substring(0, 80).trim() : clean;
    }

    private static String safeDisplayName(String value) {
        return value == null || value.trim().isEmpty() ? "Zalo contact" : value.trim();
    }

    private static String safePhone(String value) {
        if (value == null) {
            return "";
        }
        boolean plus = value.trim().startsWith("+");
        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 8 || digits.length() > 15) {
            return "";
        }
        return plus ? "+" + digits : digits;
    }

    private static String safeDirection(String direction) {
        return "incoming".equals(direction) || "outgoing".equals(direction)
                ? direction : "unknown";
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
