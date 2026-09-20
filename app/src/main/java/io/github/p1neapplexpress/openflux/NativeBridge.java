package io.github.p1neapplexpress.openflux;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * JNI bridge for libtun2socks.so + fd passing over unix socket.
 * Replaces legacy tech.p1neapplexpress.soxmax.System.
 */
public final class NativeBridge {

    private static final String TAG = "NativeBridge";
    private static final String[] BUNDLED_LIBS = {
            "libtun2socks.so",
            "libpdnsd.so",
    };

    private static volatile boolean sLoaded = false;

    private NativeBridge() {}

    public static synchronized void ensureLoaded(Context appContext) {
        if (sLoaded) return;
        try {
            for (String lib : BUNDLED_LIBS) {
                extractIfNeeded(appContext, lib);
            }
            java.lang.System.loadLibrary("system");
            sLoaded = true;
            Log.i(TAG, "Native libraries loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
            throw new RuntimeException("Native library load failure", e);
        }
    }

    private static void extractIfNeeded(Context ctx, String libName) {
        try {
            File libDir = new File(ctx.getFilesDir(), "lib");
            if (!libDir.exists() && !libDir.mkdirs()) {
                throw new IOException("Cannot create dir: " + libDir);
            }

            File out = new File(libDir, libName);
            if (out.exists()) return;

            String abi = Build.SUPPORTED_ABIS[0];
            String entryPath = "lib/" + abi + "/" + libName;

            try (ZipFile zip = new ZipFile(ctx.getPackageCodePath())) {
                ZipEntry entry = zip.getEntry(entryPath);
                if (entry == null) {
                    throw new IOException("Not in APK: " + entryPath);
                }
                try (InputStream is = zip.getInputStream(entry);
                     FileOutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
            if (!out.setExecutable(true)) {
                Log.w(TAG, "setExecutable failed for " + out);
            }
            Log.d(TAG, "Extracted " + libName + " -> " + out.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Extract failed: " + libName, e);
        }
    }

    public static native int sendfd(int fd, String sock);
    public static native void jniclose(int fd);
}
