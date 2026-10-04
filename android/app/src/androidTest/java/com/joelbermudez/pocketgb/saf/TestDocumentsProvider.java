package com.joelbermudez.pocketgb.saf;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Proveedor de prueba que habla el protocolo de DocumentsContract sobre un directorio privado
 * del APK de test. Los ids de documento son rutas relativas: {@code root}, {@code root/Sub},
 * {@code root/Sub/Juego.gb}. Un archivo cuyo nombre empieza por {@code virtual-} se anuncia como
 * documento virtual. Es de solo lectura para el cliente; el test lo provisiona con
 * {@link TestFixtures} (mediante {@code call}).
 *
 * <p>Está en Java a propósito: corre en el proceso del APK de test, que no incluye la librería
 * estándar de Kotlin (AGP la deja solo en el APK de la app).
 */
public class TestDocumentsProvider extends ContentProvider {
    public static final String AUTHORITY = "com.joelbermudez.pocketgb.test.documents";
    public static final String ROOT_ID = "root";

    private static final String[] DEFAULT_PROJECTION = {
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_FLAGS,
        Document.COLUMN_LAST_MODIFIED,
    };

    private volatile boolean denied = false;

    private File base() {
        return new File(getContext().getCacheDir(), "saf-fixture");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        try {
            switch (method) {
                case "reset":
                    denied = false;
                    deleteRecursively(base());
                    base().mkdirs();
                    break;
                case "put": {
                    File file = new File(base(), arg);
                    file.getParentFile().mkdirs();
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write(extras.getByteArray("bytes"));
                    }
                    break;
                }
                case "sparse": {
                    File file = new File(base(), arg);
                    file.getParentFile().mkdirs();
                    try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                        raf.setLength(extras.getLong("size"));
                    }
                    break;
                }
                case "mkdir":
                    new File(base(), arg).mkdirs();
                    break;
                case "deleteAll":
                    deleteRecursively(base());
                    break;
                case "deny":
                    denied = extras.getBoolean("denied");
                    break;
                case "snapshot": {
                    ArrayList<String> files = new ArrayList<>();
                    collect(base(), files);
                    result.putStringArrayList("files", files);
                    break;
                }
                default:
                    throw new IllegalArgumentException("Método desconocido: " + method);
            }
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (denied) throw new SecurityException("Permiso revocado (simulado)");
        Parsed parsed = parse(uri);
        if (parsed == null) return null;
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_PROJECTION);
        // Igual que DocumentsProvider: un documento inexistente devuelve null, no una excepción.
        try {
            if (parsed.children) {
                File[] files = resolve(parsed.documentId).listFiles();
                if (files == null) return null;
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File child : files) addRow(cursor, parsed.documentId + "/" + child.getName(), child);
            } else {
                addRow(cursor, parsed.documentId, resolve(parsed.documentId));
            }
        } catch (FileNotFoundException missing) {
            return null;
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (denied) throw new SecurityException("Permiso revocado (simulado)");
        if (!"r".equals(mode)) throw new IllegalArgumentException("El proveedor de prueba es de solo lectura");
        Parsed parsed = parse(uri);
        if (parsed == null) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(resolve(parsed.documentId), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    private static final class Parsed {
        final String documentId;
        final boolean children;

        Parsed(String documentId, boolean children) {
            this.documentId = documentId;
            this.children = children;
        }
    }

    /** {@code /tree/<id>/document/<id>[/children]}. */
    private static Parsed parse(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments.size() < 4 || !"tree".equals(segments.get(0)) || !"document".equals(segments.get(2))) {
            return null;
        }
        return new Parsed(segments.get(3), segments.size() > 4 && "children".equals(segments.get(4)));
    }

    private File resolve(String documentId) throws FileNotFoundException {
        File root = base();
        File file = ROOT_ID.equals(documentId) ? root : new File(root, documentId.substring(ROOT_ID.length() + 1));
        try {
            if (!file.getCanonicalPath().startsWith(root.getCanonicalPath()) || !file.exists()) {
                throw new FileNotFoundException(documentId);
            }
        } catch (IOException error) {
            throw new FileNotFoundException(documentId);
        }
        return file;
    }

    private static void addRow(MatrixCursor cursor, String documentId, File file) {
        boolean isDirectory = file.isDirectory();
        int flags = file.getName().startsWith("virtual-") ? Document.FLAG_VIRTUAL_DOCUMENT : 0;
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID:
                    value = documentId;
                    break;
                case Document.COLUMN_DISPLAY_NAME:
                    value = ROOT_ID.equals(documentId) ? "Juegos de prueba" : file.getName();
                    break;
                case Document.COLUMN_MIME_TYPE:
                    value = isDirectory ? Document.MIME_TYPE_DIR : "application/octet-stream";
                    break;
                case Document.COLUMN_SIZE:
                    value = isDirectory ? null : (Object) file.length();
                    break;
                case Document.COLUMN_FLAGS:
                    value = flags;
                    break;
                case Document.COLUMN_LAST_MODIFIED:
                    value = file.lastModified();
                    break;
                default:
                    break;
            }
            row.add(column, value);
        }
    }

    private static void collect(File dir, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children, Comparator.comparing(File::getPath));
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, out);
            } else {
                out.add(child.getPath() + ":" + child.length() + ":" + child.lastModified());
            }
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
