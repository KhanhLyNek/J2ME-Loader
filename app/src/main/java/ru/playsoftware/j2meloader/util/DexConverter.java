/*
 * Copyright 2026
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ru.playsoftware.j2meloader.util;

import android.util.Log;

import com.android.dx.command.dexer.DxContext;
import com.android.dx.command.dexer.Main;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Shared JAR -> DEX conversion for the installer and MIDlet repair path.
 * Serializing conversions prevents parallel installers from exhausting
 * memory on low-memory phones. DX itself is allowed two worker threads.
 */
public final class DexConverter {
    private static final String TAG = "J2ME-DexConverter";
    private static final int MAX_DIAGNOSTICS = 8192;
    private static final Object LOCK = new Object();

    private DexConverter() {}

    public static void convert(File jar, File dex) throws ConverterException {
        if (jar == null || !jar.isFile() || !jar.canRead() || jar.length() == 0) {
            throw new ConverterException("Input JAR is missing or unreadable: " + jar);
        }
        if (dex == null || dex.getParentFile() == null || !dex.getParentFile().isDirectory()) {
            throw new ConverterException("DEX output folder is unavailable: " + dex);
        }

        int classCount = 0;
        try (ZipFile archive = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    classCount++;
                }
            }
        } catch (IOException e) {
            throw new ConverterException("Invalid or unreadable JAR archive: " + jar.getName(), e);
        }
        if (classCount == 0) {
            throw new ConverterException("JAR has no Java class files: " + jar.getName());
        }

        // One conversion at a time avoids uncontrolled DX memory pressure.
        synchronized (LOCK) {
            final long start = android.os.SystemClock.elapsedRealtime();
            BoundedLog diagnostics = new BoundedLog(MAX_DIAGNOSTICS);
            OutputStream quietOutput = new OutputStream() {
                @Override public void write(int b) {}
            };
            DxContext dxContext = new DxContext(quietOutput, diagnostics);
            Main.Arguments args = new Main.Arguments(dxContext);
            args.fileNames = new String[]{jar.getAbsolutePath()};
            args.outName = dex.getAbsolutePath();
            args.jarOutput = false;
            args.coreLibrary = true;
            args.optimize = false;
            args.warnings = false; // Skip noisy bytecode warnings; retain fatal DX errors.
            args.numThreads = Math.min(2, Math.max(1, Runtime.getRuntime().availableProcessors()));
            try {
                Log.i(TAG, "Converting " + jar.getName() + ": " + classCount
                        + " classes, DX threads=" + args.numThreads);
                int status = new Main(dxContext).runDx(args);
                if (status != 0) {
                    throw new ConverterException("DX returned " + status + ": " + diagnostics.summary());
                }
                verifyDex(dex);
                Log.i(TAG, "Converted " + jar.getName() + " in "
                        + (android.os.SystemClock.elapsedRealtime() - start) + " ms");
            } catch (ConverterException e) {
                deleteIncompleteDex(dex);
                throw e;
            } catch (Throwable e) {
                deleteIncompleteDex(dex);
                String reason = diagnostics.summary();
                String message = "JAR to DEX failed (" + jar.getName() + "): " + reason;
                if (e instanceof OutOfMemoryError) {
                    message = "Not enough memory to convert " + jar.getName() + ". " + reason;
                }
                Log.e(TAG, message, e);
                throw new ConverterException(message, e);
            }
        }
    }

    private static void verifyDex(File dex) throws IOException {
        byte[] header = new byte[4];
        try (FileInputStream in = new FileInputStream(dex)) {
            if (in.read(header) != header.length
                    || header[0] != 'd' || header[1] != 'e'
                    || header[2] != 'x' || header[3] != '\n') {
                throw new IOException("DX output has no valid DEX header: " + dex);
            }
        }
    }

    private static void deleteIncompleteDex(File dex) {
        if (dex.exists() && !dex.delete()) {
            Log.w(TAG, "Unable to remove incomplete DEX: " + dex);
        }
    }

    private static final class BoundedLog extends OutputStream {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final int maxBytes;

        BoundedLog(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public synchronized void write(int b) {
            if (output.size() < maxBytes) {
                output.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] data, int offset, int length) {
            int remaining = maxBytes - output.size();
            if (remaining > 0) {
                output.write(data, offset, Math.min(length, remaining));
            }
        }

        synchronized String summary() {
            String value = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            if (value.isEmpty()) {
                return "See Android logcat for the converter exception.";
            }
            // Present the actionable first error, not pages of warning output.
            int newline = value.indexOf('\n');
            String firstLine = newline >= 0 ? value.substring(0, newline) : value;
            return firstLine.length() > 260 ? firstLine.substring(0, 260) : firstLine;
        }
    }
}
